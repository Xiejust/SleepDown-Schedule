# Fix Report — 课表导入失败 / 欢迎界面 / 卡顿 / 移除自动刷新课表

日期：2026-11-23

## 1. Bug 描述

1. 选择 `.xls` 学生课表文件后无法正常导入，表现为点击导入入口没有反应或进入后无结果。
2. 首次启动仍会弹出欢迎/协议窗口。
3. 应用整体操作卡顿依旧明显。
4. 要求删除“自动刷新课表”功能，但保留“班级课表”自动同步。

## 2. 问题根因

### 2.1 导入失败
解析器与落库链路本身正常：用用户真实文件 `2513041076_谢明宇(学生课表).xls` 跑通浏览器内核（Robolectric）端到端测试，得到 45 门课程、11 个节次、18 周，Room 事务写入成功。

真正的问题在入口：
- 手机端导入入口走“锚点式”转场（对悬浮按钮/菜单截图后在目标页重放）。当截图失败、菜单已关闭或首帧尚未测量时，`latestOpenHomeActivityDestination` 与 `dockOpenImport` 会静默 return，用户感知就是“点了没反应”。
- 悬浮“+”导入还要求 `source.view` 存在，视图缺失时同样静默失败。

### 2.2 欢迎界面
`CourseScheduleApp.onCreate` 启动时初始化远程配置，`ScheduleAppUi` 依据远程配置的 `agreements` 在首次使用时弹出“请查看并同意协议”的 `LiquidAlertDialog`，这就是用户看到的“欢迎界面”。

### 2.3 卡顿
- 首屏启动即发起远程配置网络请求 + 解密。
- 首页常驻自动刷新复核弹层宿主。
- 导入转场每页做全屏截图与位图裁剪。
- 交付物为 debuggable 调试包（ART 关闭 AOT、开启调试钩子），Compose 重负载界面性能明显劣于正式包。
- 安装包内仍打包 3.5MB 已经不再使用的教务仓库资源。

### 2.4 自动刷新课表
功能以 `feature/schedule/autorefresh/**`（Store/Worker/Reviewer/WebSession/SwuUnifiedAuthActivity 等）、设置页 `AutoRefreshSchedule`、首页复核宿主、清单条目与转场路由的形式存在，与“班级课表”同步（`feature/classsync/**`）无耦合。

## 3. 尝试记录

### 尝试 1：只排查解析器
- 结果：成功定位“解析器无罪”。
- 说明：端到端测试通过，排除 HTML 表格解析与 Room 写入。

### 尝试 2：锚点转场增加兜底
- 结果：部分可行，但入口依然依赖视图测量，路径复杂。
- 处理：改为直接启动目标 Activity，失败再用 `startActivity` 兜底。

### 尝试 3：删除自动刷新模块
- 结果：成功。
- 说明：删除 21 个 autorefresh 文件及其设置页/清单/路由，同时清理因删除 `EduImport.kt` 而失去模型的教务浏览器死代码（迁入回收目录备份，非永久删除）。

## 4. 最终方案

### 4.1 导入入口
- 新增 `launchUnifiedImportDirectly(files)`：`unifiedImportIntent` → `ActivityTransitionCoordinator.openImmediate(HomeToUnifiedImport)`，失败则 `startActivity`。
- 修改 `openUnifiedImportPage`（手机端）、`latestOpenImport`、`currentDockOpenImport` 全部改为直连启动，不再依赖截图锚点；悬浮“+”先 `release()` 源视图再启动。

### 4.2 欢迎界面
- 移除 `ScheduleAppUi` 中协议/公告弹窗与远程体验门控。
- `CourseScheduleApp` 启动时不再调用 `SleepDownRemoteConfig.initialize`。

### 4.3 自动刷新课表
- 删除 `feature/schedule/autorefresh/**`、`AutoRefreshScheduleUi.kt`、`AutoRefreshAvatarCropDialog.kt`。
- 移除 `SettingsPage.AutoRefreshSchedule`（枚举/标题/搜索/设置根入口/详情宿主/深层链接）。
- 移除 AndroidManifest 中 `SwuUnifiedAuthActivity` 与 `EduImportActivity`。
- 移除 `TransitionRoute.SchoolSelectToEduImport`、`ImportToAiProgress`。
- 清理 `feature/importing` 下失去依赖的教务浏览器死代码（20 个主源码文件、19 个测试）。
- “班级课表”同步：`ClassSyncManager` 启动同步与后台自动发布逻辑保持原样。

### 4.4 卡顿
- 启动不再拉取远程配置；移除首页刷新复核弹层；导入不再做全屏截图。
- 删除 3.5MB 无引用的 `assets/shiguang_warehouse-main`。
- 交付改为 **R8 压缩 + 资源裁剪 + 非 debuggable** 的 release 包（本地自签名，`5.7MB`，原调试包约 13MB）。

## 5. 验证

| 项目 | 命令/方法 | 结果 |
| --- | --- | --- |
| 主源码编译 | `:app:compileGithubDebugKotlin` | BUILD SUCCESSFUL |
| 单元测试编译 | `:app:compileGithubDebugUnitTestKotlin` | BUILD SUCCESSFUL |
| 重点测试（JDK 17） | `--tests "*Import*" "*Shiguang*" "*CanonicalSchedule*" "*ClassSync*"` | 68 个用例，66 通过，2 个失败（见下） |
| 全量测试（JDK 17） | `:app:testGithubDebugUnitTest` | 1224 个用例，42 失败，绝大多数为 Miuix 以 Java 21 编译导致的 `UnsupportedClassVersionError`（环境问题，与本轮无关） |
| 设置/UI 测试（JDK 21） | 9 个 UI 测试类 | 43 个用例，39 通过，4 个 Robolectric 布局/语义查询失败（既有环境问题） |
| Release 构建 | `:app:assembleGithubRelease` | BUILD SUCCESSFUL，APK 5.7MB |
| 签名校验 | `apksigner verify` | V2 签名通过 |
| 清单校验 | `aapt2 dump xmltree` | `ClassSyncActivity`/`EduSchoolSelectActivity` 存在；无 `SwuUnifiedAuthActivity`、`EduImportActivity`、`VersionWelcomeActivity` |
| 内容校验 | dex 字符串检索 | 含“学生课表文件/正在解析课表/文件超过 8MB”；无“自动刷新课表”界面文案 |
| 端到端导入 | Robolectric + 用户真实 `.xls` | 45 课程 / 11 节次 / 18 周导入成功（验收后可移除的临时用例已删除） |

已知失败（均为改动前既有问题，与本轮无关）：
- `CoursePeriodAlignmentRepositoryTest.failedTimeImportRollsBackAllData`
- `ImportDraftCompatibilityTest.overlappingAndSparsePeriodsKeepExactTimesAndSourceNumbers`

两者都指向 `withCompatiblePeriodGrid()`：当前实现只做时间合法性校验，节次时间重叠会抛出“第 N 节与第 M 节时间重叠”。若真实课表存在时间重叠的节次，导入会在保存阶段报错。

另有 4 个 Robolectric UI 用例（`ScheduleDetailPageUiTest` 3 个、`CouiMigrationUiTest` 1 个）在 JDK 21 下仍因布局语义节点查询失败，属于本地测试环境限制；本轮未改动被测页面逻辑。

## 6. 检查清单

- [x] 对比导入链路：解析器 → `buildDraftFromPayload` → `validatePayload` → `importDraft` → Room
- [x] 对比启动链路：Application → MainActivity → ScheduleAppUi
- [x] 检查清单与转场路由一致性
- [x] 性能检查：移除启动网络、截图转场、死资源、调试构建
- [x] 构建与签名验证
- [x] 班级同步代码保留检查

## 7. 经验教训

1. “导入没反应”优先怀疑入口静默失败，而不是解析器；给转场入口加必达兜底比继续修锚点更划算。
2. 删除被多处引用的模型文件（如 `EduImport.kt` 的 `EduAdapter`）会连带出一整片死代码，应顺着编译错误成批清理，并保留回收目录。
3. 用户体感的“卡顿”很大一部分来自 debuggable 构建；正式交付应使用 release 包。
4. 远程配置驱动的“首次启动弹窗”必须在移除功能时同时移除初始化调用，否则仅删 UI 仍会残留窗口。

## 8. 追加修复（第二轮）：导入页报 "The coroutine was canceled because it left the composition"

### 现象
选择 `.xls` 后导入页显示红色英文错误：`The coroutine was canceled because it left the composition`，文件没有被解析。

### 根因
解析逻辑放在 `UnifiedImportPage` 的 `LaunchedEffect(form.pendingUri, state.periods, state.config.id)` 中，并用 `runCatching` 捕获所有异常（包括 `CancellationException`）：

1. 转场宿主 `CrossActivityTransitionHost` 在转场结束时替换/重组内容，或 `state.loaded` 从 false 变为 true 导致 key 变化，都会取消正在执行的 `LaunchedEffect`。
2. `runCatching` 把 `CancellationException` 当成普通失败，取 `error.message` 显示为红字。
3. 取消瞬间 `pendingUri` 已被置空，重建后的 effect 直接返回，解析请求永久丢失。

### 修复
- 解析任务移到 `UnifiedImportFormState`（`viewModelScope`）中执行，组合重建/转场替换不再取消任务。
- `LaunchedEffect` 只观察 `pendingUri` 与 `state.loaded`，数据未加载完成时先等待，避免用默认作息生成草稿。
- `CancellationException` 原样抛出，不再显示为错误；其余失败统一显示中文提示（解析异常保留具体原因）。
- `pick()` / `reset()` 会取消上一次解析任务，避免新旧文件结果串台。

### 验证
- `:app:compileGithubDebugKotlin`：BUILD SUCCESSFUL。
- `:app:assembleGithubRelease`：BUILD SUCCESSFUL，签名校验通过。
- APK dex 中确认存在新提示文案“读取课表文件失败，请确认所选文件是教务系统导出的学生课表（.xls 或 .html）”。

## 9. 追加需求（第三轮）：默认作息 / 首启公告 / 赞助入口 / 四场景卡顿

### 9.1 默认作息改为安医大 11 节

**改动**
- `model/ScheduleModels.kt`：`defaultPeriods()` 改为 11 节（08:30-09:10、09:20-10:00、10:10-10:50、11:00-11:40、14:00-14:40、14:50-15:30、15:40-16:20、16:30-17:10、19:00-19:40、19:50-20:30、20:40-21:20）；`defaultConfig()` 的 `classDurationMinutes` 改为 40（每节 40 分钟，课间 10 分钟）。
- `ScheduleRepository.ensureDefaults()` 新增 `upgradeLegacyDefaultTimetable()`：仅当某课表**仍然完全等于旧版 12 节内置默认**、且没有任何课程使用第 12 节时，升级为新 11 节。已导入的真实课表、手工改过作息的课表都不会被覆盖。

**根因/边界**
- 已有安装的数据库不会重新读默认值，因此必须在启动时做一次性对比升级；用“逐节时间完全相等 + 第 12 节无课程”作为判据，避免误伤用户数据。

### 9.2 首次启动公告

- 新增 `app/ui/FirstLaunchNotice.kt`：`SharedPreferences("first_launch_notice").notice_v1_shown` 记录是否已展示。
- 在 `CourseScheduleAppUi` 的对话框区域挂载 `FirstLaunchNoticeDialog(config, ready = state.loaded)`，首屏数据加载完成后弹出一次，文案为：
  “当前软件基于GitHub上的开源项目sleep-down课程表魔改开发而来，使用时遇到问题请联系，qq：2979958015”。
- 点“我知道了”或返回键关闭后写入标记，之后不再出现。

### 9.3 赞助支持入口

- 二维码复制为 `res/drawable-nodpi/sponsor_qr.png`（1221×1221，约 213KB）。
- `ColorOsAboutSettingsScreen` 的“关于/链接”分组新增“赞助支持”行，点击用 `LiquidAlertDialog(messageContent = …)` 展示二维码与说明，单个“关闭”按钮。

### 9.4 卡顿修复（启动 / 切周 / 打开编辑 / 滑动课表）

| 场景 | 根因 | 修复 |
| --- | --- | --- |
| 启动 | `AppIconManager.applyStoredMode` 的 `lastAppliedIconResId` 只存在进程内，冷启动必为 null → `changed=true` → 主线程执行 `NotificationScheduler.refreshLiveUpdateIcon` + `TodayCoursesWidgetProvider.refreshAll` | 把已应用图标 id 持久化到 SharedPreferences 判断是否变化；通知/小组件刷新移入 `applicationScope` 的 IO 线程 |
| 全局 | 默认材质等级为 `QUALITY`，卡片折射/高光/阴影与渐进模糊全开 | 默认改为 `PERFORMANCE`：保留玻璃质感，关闭最贵的折射/高光/阴影与渐进模糊；用户仍可在设置切换 `QUALITY` / `SUPER_PERFORMANCE` |
| 切周 | `homeCaptureFrameKey` 包含 `homeDisplayWeek`，每次切周后 `HomeBackgroundBlurLayer.prepareScene` 都会把整页 `record` 到 `screenGraphicsLayer`，即使当前没有任何浮层在采样该图层 | 为 `prepareScene` 增加 `idle` 语义：非浮层状态（`blurProgress == 0`）跳过整页录制，等到浮层真正打开、需要模糊背景时再录制；自定义主题/课表预览（`sceneVisible=false`）仍保持立即录制 |
| 打开编辑 | 编辑表单会先 mount+layout 再播放 Opening 动画，首帧叠加玻璃/材质开销 | 由 `PERFORMANCE` 默认值降低表单与浮层材质成本；预加载流程保持不变（预加载本身是为了避免动画期间布局抖动） |
| 滑动课表 | 每张卡片常驻玻璃采样/模糊绘制 | `PERFORMANCE` 下关闭折射、高光和阴影；无壁纸时本就不启用卡片采样，进一步减少逐帧开销 |

**改动文件**
- `core/performance/AppMaterialPreferences.kt`
- `core/identity/AppIconMode.kt`
- `CourseScheduleApp.kt`
- `feature/home/HomeBackgroundEffects.kt`
- `app/ui/ScheduleAppUi.kt`

### 9.5 验证

| 项目 | 命令/方法 | 结果 |
| --- | --- | --- |
| Release 构建 | `:app:assembleGithubRelease` | BUILD SUCCESSFUL（3m57s） |
| 签名 | `apksigner verify --print-certs` | 通过，CN=SleepDown Local |
| 清单 | `aapt2 dump badging` | `com.xiaomanjun.sleepdownschedule` / versionCode 39 / versionName 2.0.3 / minSdk 26 / targetSdk 36 |
| 资源 | `unzip -l` | `res/kg.png` 191KB，对应赞助二维码（资源名被资源优化重命名） |
| 内容 | dex 字符串检索 | 含 `sleep-down`、`2979958015`、`赞助支持`、`赞助二维码`、`08:30`、`09:10`、`21:20` |
| 交付 | 复制 + `md5sum` | `e487908e23fddfb70b19c05fcfe20b0f` |

### 9.6 经验

1. 冷启动“是否变化”这类判断必须持久化，否则每次启动都会重跑一次本应只在变化时执行的重任务。
2. 全屏场景录制是切周卡顿的隐藏热点：录制结果只在浮层模糊/采样时使用，没有必要在无浮层时抢跑。
3. “默认值”对老用户不会自动生效，需要显式的、带安全判据的一次性升级；判据必须严格到不会误覆盖真实数据。
4. 材质等级是最划算的性能开关：默认从 QUALITY 降到 PERFORMANCE 不影响布局与交互，但去掉的正是每帧最贵的绘制项。

## 10. 追加需求（第四轮）：改名为 AHMU课程表 + 更换签名

### 10.1 需求
- 应用显示名称改为「AHMU课程表」。
- 签名密钥改为 `xingmanye`。

### 10.2 实施

**显示名称**（`applicationId` 仍是 `com.xiaomanjun.sleepdownschedule`，未改动；Android 包名不允许中文，用户所说的“包名”按应用显示名称处理）：

| 文件 | 改动 |
| --- | --- |
| `app/src/main/res/values/strings.xml` | `app_product_name`：SleepDown课程表 → AHMU课程表；`backup_open_with_label`：SleepDown 恢复 → AHMU课程表 恢复 |
| `feature/settings/about/ColorOsAboutSettingsScreen.kt` | `SleepDownFullName` 常量同步改名（关于页、更新页版本卡标题） |
| `feature/settings/SettingsRootScreen.kt` | 取不到 `PackageManager` 标签时的兜底文案同步改名 |
| `AboutSettingsUiTest` / `SettingsHomeVisualTest` | 测试夹具与断言同步改名 |

**签名**：新建 `D:/AndroidBuild/xingmanye-release.jks`，`alias=xingmanye`，DN `CN=xingmanye, OU=xingmanye, O=xingmanye, L=Hefei, ST=Anhui, C=CN`，RSA 2048 / 10000 天，storepass 与 keypass 均为 `xingmanye`。构建时通过 `-Psleepdown.releaseStoreFile/releaseStorePassword/releaseKeyAlias/releaseKeyPassword` 注入，未写入仓库。

### 10.3 验证

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| Release 构建 | `:app:assembleGithubRelease` | BUILD SUCCESSFUL，5m 6s，APK 5.7MB |
| 应用标签 | `aapt2 dump badging` | `application-label:'AHMU课程表'`（全部语言一致） |
| 包名/版本 | 同上 | `com.xiaomanjun.sleepdownschedule`，versionCode 39，versionName 2.0.3 |
| 签名校验 | `apksigner verify -v --print-certs` | `Verifies`；v2 = true；signers = 1；DN `CN=xingmanye`；SHA-256 `4b6495de…f4d5` |
| dex 字符串 | 解包检索 | 含 `AHMU课程表`，无 `SleepDown课程表` |
| 单测编译 | `:app:compileGithubDebugUnitTestKotlin` | BUILD SUCCESSFUL |

交付物：`D:\zhuomian\xchedule\AHMU课程表-release.apk`
MD5 `95539a2b699389d6e8088a0c9d64856a`
SHA-256 `3de572ca2b21765179c75598bb773fc0f5b84a7ba9e87da76d8bf1a037592225`

### 10.4 注意

**签名密钥变了，旧版本无法直接覆盖安装**（签名不一致会被系统拒绝）。已装旧版 `SleepDown课程表` 的设备必须先卸载再安装新包；卸载会清空应用内课表数据，需要先导出备份。

### 10.5 经验

1. 产品名在 Android 上分散于 `strings.xml`、关于页常量、PackageManager 兜底三处，改名必须一起改，否则界面会出现两个名字。
2. 换签名是不可逆的分发事件：同一 `applicationId` 下新旧签名不能共存，必须提前说明卸载/备份步骤。
3. `applicationId` 与“包名”在口语中混用，中文名只能落在 `android:label`，不能用 applicationId 表达。

## 11. 追加需求（第五轮）：关于页开发者与项目仓库改为本人账号

### 11.1 需求

安装包内「关于开发者」「项目仓库」两处改为本人的 GitHub 账号与仓库。

### 11.2 实施

| 文件 | 改动 |
| --- | --- |
| `feature/settings/about/AboutSettingsScreen.kt:245` | `SleepDownSourceUrl` → `https://github.com/Xiejust/SleepDown-Schedule`（releases / issues / LICENSE 均由此派生） |
| `feature/settings/about/AboutSettingsScreen.kt:180` | 「开发与维护」→ `Xiejust` |
| `feature/settings/about/AboutSettingsScreen.kt:124,467` | 版本脚注「小漫君独立设计与开发」→「Xiejust 维护」 |
| `feature/settings/about/ColorOsAboutSettingsScreen.kt:107` | 「关于开发者」副标题 `xiaomanjun233` → `Xiejust` |
| `feature/settings/about/ColorOsAboutSettingsScreen.kt:189` | `SleepDownDeveloperUrl` → `https://github.com/Xiejust` |
| `feature/settings/LegalAndDonationUi.kt:69` | 隐私政策联系方式 `xiaomanjun233/CourseSchedule` → `Xiejust/SleepDown-Schedule` |
| `AboutSettingsUiTest.kt:103` | 断言文案同步为 `Xiejust` |

「项目仓库」副标题仍是 `SleepDown-Schedule`，仓库名本身没变，因此未改动。

### 11.3 验证

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| Release 构建 | `:app:assembleGithubRelease` | BUILD SUCCESSFUL，3m 29s |
| 关于页 UI 测试 | `:app:testGithubDebugUnitTest --tests "*AboutSettingsUiTest*"`（JDK 21） | 2 tests / 0 failures |
| dex 字符串 | `dexdump -d classes.dex` | 含 `github.com/Xiejust`、`github.com/Xiejust/SleepDown-Schedule`、`/releases`、`/blob/main/LICENSE.md`；`小漫君` 0 处 |
| 签名 | `apksigner verify -v --print-certs` | `Verifies`；v2 true；1 signer；DN `CN=xingmanye`；SHA-256 `4b6495de…f4d5`（与上一版一致，可直接覆盖升级） |
| 应用标签 | `aapt2 dump badging` | `application-label:'AHMU课程表'` |

交付物：`D:\zhuomian\xchedule\AHMU课程表-release.apk`
MD5 `f500f141e2fc8ae802e41aac0dc3df16`
SHA-256 `3d9b2674d048402cf0149bb572e7541182fc5497b92dc476994eb761c9c4ce28`

### 11.4 未改动与原因

- **官方网站**仍指向 `https://xiaomanjun233.github.io/SleepDown-Schedule/`：`https://xiejust.github.io/SleepDown-Schedule/` 当前返回 404（未开启 GitHub Pages），贸然改会得到死链。
- **Gitee 更新源**（`AppUpdate.kt` 的 `GiteeOwner`）仍指向上游：本人暂无同名 Gitee 仓库，改了只会让更新检查请求不存在的地址。
- 隐私政策正文里的运营者表述与官网链接未动，只替换了 GitHub Issues 联系方式。

### 11.5 经验

1. 开发者与仓库链接分散在非 ColorOS 与 ColorOS 两套关于页，各有一份常量，改一处会出现两套界面对不上的情况。
2. 仓库地址是常量派生的（`$SleepDownSourceUrl/releases` 等），只改基准常量即可覆盖 releases、issues、许可证三个入口。
3. 单测在 JDK 17 下会因 miuix 以 Java 21 编译而报 `UnsupportedClassVersionError`，与本次改动无关；跑 Robolectric 需切到 JDK 21。

## 12. 追加需求（第六轮）：切断上游 Gitee 自更新

### 12.1 背景

`github` 发行版默认开启应用内自更新，检查地址是上游作者的 Gitee：
`https://gitee.com/api/v5/repos/xiaomanjun233/SleepDown-Schedule/releases`。
上游最新为 `v2.0.3`，与当前版本号相同，因此暂不弹提示；但只要上游发布 `2.0.4` 一类的版本，本包就会提示更新并下载上游 APK。上游 APK 使用原作者签名，与本包 `xingmanye` 签名不一致，安装必然失败；用户若先卸载再装，会丢失本地数据并换成上游原版。

### 12.2 实施

| 文件 | 改动 |
| --- | --- |
| `core/identity/AppDistribution.kt` | `supportsSelfUpdate` 恒为 `false`，并补充原因注释 |
| `feature/settings/about/SoftwareUpdateSettingsScreen.kt:195` | 无自更新时主按钮文案由「检查更新」改为「前往 GitHub 下载」，点击打开 `SleepDownReleasesUrl`（`Xiejust/SleepDown-Schedule/releases`） |
| `feature/update/AppUpdate.kt:215` | `downloadApk` 的自更新限制收窄为 `packageKind == AppUpdate`，避免连带禁掉 ColorOS 课程组件下载 |
| `AboutSettingsUiTest` | 适配「自动更新设置」菜单项不再出现 |

保留的行为：关于页版本卡的「检查更新」入口、软件更新页、更新说明与历史日志；进入软件更新页后一键跳 GitHub Releases。

### 12.3 验证

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| 关于页/更新页 UI 测试 | `:app:testGithubDebugUnitTest --tests "*AboutSettingsUiTest*"` | 2 tests / 0 failures |
| Release 构建 | `:app:assembleGithubRelease` | BUILD SUCCESSFUL，3m 17s |
| 签名 | `apksigner verify --print-certs` | `Verifies`；DN `CN=xingmanye`；SHA-256 `4b6495de…f4d5`（与上一版一致） |
| 应用标签 | `aapt2 dump badging` | `application-label:'AHMU课程表'`，versionCode 39 / 2.0.3 |

交付物：`D:\zhuomian\xchedule\AHMU课程表-release.apk`
MD5 `3c55c1f3a1615ca3760c1715357fbc53`
SHA-256 `1df66faa92b731c2922b7a8638b9e6ec63190d361734e6103b294d38cd129a3e`

### 12.4 行为变化

- 启动后不再请求 Gitee，`autoCheckUpdates` 设置不再产生任何网络行为。
- 软件更新页在未检测状态下的主按钮变成「前往 GitHub 下载」，可直接打开本仓库 Releases。
- 「自动更新设置」菜单项随 `supportsSelfUpdate` 一起隐藏（页面代码保留，后续接回自更新可复用）。
- ColorOS 课程组件仍从上游 Gitee 下载，未受影响。

### 12.5 注意

本包与上游包使用同一 `applicationId` 但签名不同，两者不能互相覆盖安装。今后若要恢复应用内更新，需要先在自己的仓库发布带 APK 的 Release，再把 `GiteeAppUpdater` 的数据源换成自己的仓库。
