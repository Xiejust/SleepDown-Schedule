# iOS 兼容方案评估报告

日期：2026-11-23
评估对象：SleepDown 课程表（当前 Android 精简版 release，5.7MB，R8 压缩）
评估目的：判断「让 iPhone 用户也能用课表」的最优路径，给出工作量量级与风险，供决策。

> **前置事实（必须写在最前面）**
> 1. 当前工程是 **Android 原生**：Kotlin + Jetpack Compose + COUI/Miuix 材质 + Room + WorkManager + AppWidget + 图标别名。**不是** Flutter / React Native / Compose Multiplatform。
> 2. **APK 不能装到 iPhone 上**，iOS 只接受经 Apple 证书签名的 IPA，安装渠道为 App Store / TestFlight / Ad-Hoc 分发。
> 3. 本机是 Windows，**没有 macOS 和 Xcode**，因此无法在本地编译、签名、跑模拟器、验证任何原生 iOS 产物。本报告中的工期是**工程量级估算**，不是交付承诺；真实排期必须以一台 Mac（或云端 macOS CI）为前提。
> 4. Apple 开发者账号（上架必需）：个人/公司 $99/年。

---

## 1. 结论摘要

| 方案 | 一句话结论 | 建议 |
| --- | --- | --- |
| **A. PWA（网页版，可添加到主屏幕）** | 2.5–4 周可上线，iPhone 立即可用；无小组件、无 Live Activity、后台通知受限 | **第一步，建议做** |
| **B. Kotlin 共享逻辑（KMP）+ SwiftUI 原生界面** | 16–22 周，iOS 体验最好（原生 Liquid Glass、小组件、Live Activity 全有） | **第二步，用户量验证后再投** |
| **C. Compose Multiplatform 全量迁移** | 17–25 周，UI 仍要重写 60%+，材质层几乎全废，性价比最差 | **不建议** |
| **D. Flutter 重写** | 现有 Kotlin 代码 0 复用，且要推翻一套已完成的设计体系 | **不考虑** |

**推荐路线：先 A 后 B。** 用 PWA 花小成本把 iPhone 可用性做出来（复用现有班级同步后端、复用课表解析算法），验证 iOS 侧真实用户规模后，再投入原生版本。

---

## 2. 现有代码基线（实测数字）

### 2.1 规模

| 项目 | 文件数 | 代码行数 |
| --- | --- | --- |
| 主源码 `app/src/main/java` | 511 | 100,853 |
| 单元测试 `app/src/test` | 198 | 约 24,000 |
| 仪器测试 `app/src/androidTest` | 4 | 约 1,700 |
| 测试用例总数 | — | 1,216 单元 + 28 仪器 |

按包分布：

| 包 | 文件 | 行数 | 说明 |
| --- | --- | --- | --- |
| `feature` | 217 | 56,651 | 业务界面层，最大头 |
| `core` | 114 | 13,834 | 其中 `core/ui` 88 文件 / 10,980 行，几乎全绑定 COUI/Miuix |
| `glass` | 48 | 6,819 | 玻璃材质渲染，Android 专属 |
| `app` | 12 | 5,812 | 组装层、Activity、导航 |
| `transition` | 22 | 4,034 | 页面转场框架 |
| `domain` | 22 | 3,805 | 业务规则，**几乎与平台无关** |
| `data` | 11 | 3,612 | Room DAO / 仓储 / 迁移 |
| `platform` | 16 | 1,557 | 平台能力封装 |
| `model` | 12 | 644 | 数据模型 |

`feature` 内部：

| 模块 | 文件 | 行数 | 迁移难度 |
| --- | --- | --- | --- |
| home（周视图/日视图/首页） | 73 | 17,619 | 全部重写 |
| settings（设置全量） | 36 | 8,717 | 全部重写 |
| widget（桌面小组件） | 13 | 5,706 | 全部重写（Android AppWidget） |
| course（课程编辑/详情） | 15 | 5,818 | 全部重写 |
| backup（备份导入导出） | 23 | 5,719 | 逻辑可复用，UI 重写 |
| schedule（多课表管理） | 9 | 3,207 | 重写 |
| importing（学生课表解析） | 13 | 2,812 | **解析算法可复用** |
| reminder（提醒/闹钟） | 12 | 2,341 | 重写（WorkManager → 本地通知） |
| classsync（班级同步） | 6 | 1,319 | **逻辑可复用**，客户端换 URLSession |
| experimental / update / coloros / agent | 17 | 3,393 | 可裁剪 |

### 2.2 Android 耦合度（关键指标）

| 指标 | 数量 | 占比 |
| --- | --- | --- |
| 含 `android.*` / `androidx.*` / `com.itos.coui` import 的文件 | 196 / 511 | 38.4% |
| 完全平台无关的纯 Kotlin 文件 | 102 / 511 | 20.0% |
| 纯 Kotlin 代码行数 | 10,921 / 100,853 | 10.8% |

分模块耦合情况：

| 包 | 文件 | Android 耦合 | 复用判断 |
| --- | --- | --- | --- |
| `domain` | 22 | **1** | 直接搬运，最有价值 |
| `model` | 12 | 4（Room 注解） | 换注解即可复用 |
| `data` | 11 | 10 | 持久化层需替换 |
| `feature` | 217 | 182 | 界面重写，逻辑剥离 |
| `glass` | 48 | 45 | 无法复用 |
| `core` | 114 | 103 | UI 部分无法复用 |

### 2.3 Android 专属能力清单

| 能力 | 使用情况（实测） | iOS 对位方案 | 迁移成本 |
| --- | --- | --- | --- |
| Manifest 组件 | 25 个 `activity` + 9 个 `activity-alias`（图标别名）+ 9 个 `receiver` + 1 个 `service` + 1 个 `provider` | SwiftUI App 生命周期 / Xcode target / App Intents | 高 |
| 桌面小组件 | `android.appwidget` 9 文件 / 5,706 行 | WidgetKit + SwiftUI | 高，需重写布局体系 |
| 后台任务 | `androidx.work` 4 文件（提醒、同步、备份） | BGTaskScheduler + UNUserNotificationCenter | 中 |
| 本地数据库 | Room 17 文件；DB version **50**，**48 个迁移** / 1,356 行迁移代码；14 实体 + 1 视图 + 7 DAO | GRDB / Core Data / SwiftData | 高（迁移链需重设计） |
| 液态玻璃材质 | `RuntimeShader` 8 文件、`RenderEffect` 21 文件、`GraphicsLayer` 24 文件 | iOS 26 原生 `.glassEffect()` 或自绘 Metal/CoreAnimation | 高（但 iOS 26 原生能力反而更强） |
| 图标切换 | 9 个 `activity-alias` | `UIApplication.setAlternateIconName` | 低 |
| 输入法/WebView | `androidx.webkit`、`browser` | WKWebView | 中 |
| 通知与 Live Activity | 常驻通知、点名状态 Live Update | UNUserNotificationCenter + ActivityKit | 中 |
| 花屏/壁纸采样 | `PixelCopy`、`HardwareRenderer` | 需完全不同的取色方案 | 高 |
| Shizuku / ColorOS 适配 | 5 文件 | iOS 无对应，直接废弃 | 可删 |
| JVM 时间 API | `java.time` 79 文件 / 153 处引用；`java.util` 36 文件 | Swift Foundation / kotlinx-datetime | 中（机械替换但面广） |

> 结论：**UI 与系统集成层约 85% 必须重写**，真正能搬的是 `domain` + `model` + 解析/同步/备份的业务算法，约 **6,000–8,000 行**，占总量 6%–8%。

---

## 3. 可复用资产盘点

| 资产 | 位置 | 复用方式 | 复用度 |
| --- | --- | --- | --- |
| 业务规则（周次计算、冲突检测、节次对齐、提醒时间推导） | `domain/` 22 文件 | Kotlin 共享 / 直译 Swift | 90% |
| 学生课表 `.xls`（HTML 表格）解析器 | `feature/importing/` | Kotlin 共享，或用 TypeScript 重写（算法一致） | 85% |
| 班级同步客户端 | `feature/classsync/` 1,319 行 | 换 HTTP 实现即可，协议不变 | 80% |
| 班级同步后端 | Cloudflare Worker + D1（`functions/`、`migrations/`） | **服务端 100% 复用**，iOS 只是另一个客户端 | 100% |
| 备份/导出 JSON 格式 | `feature/backup/` | 作为跨平台数据迁移格式（优于直接搬 Room schema） | 70% |
| 数据模型定义 | `model/` 644 行 | 换 Room 注解为普通 data class | 85% |
| 设计规范（配色、间距、排版、玻璃参数） | `docs/`、`core/ui` | 作为设计 spec 复用，不是代码复用 | — |
| UI 组件、转场、材质、小组件、通知 | 其余 | **不可复用** | 0% |

**关键判断**：搬代码的收益主要不在「省工期」，而在于**业务规则已经过 1,216 个测试验证**，iOS 端重写时可以直接参照这些用例，避免重新踩坑（尤其是节次重叠、跨周、跨校区、异区作息这类历史 bug）。

---

## 4. 三方案对比

### 4.1 方案 A：PWA（网页版）

**形态**：Safari 打开 → 「添加到主屏幕」→ 全屏运行，可离线。托管在已有 Cloudflare Pages 上（`sleepdown-site` 已有静态站，可直接扩一个应用子域）。

| 维度 | 评估 |
| --- | --- |
| 交付物 | 一个可安装的 Web 应用 URL |
| 验证能力 | 本机即可开发验证（Chrome/Safari 响应式 + 真机 Safari 访问局域网） |
| 功能覆盖 | 周视图 / 日视图 / 课程编辑 / 多课表 / 导入 / 备份：可做。小组件、Live Activity、精确后台提醒：**做不了** |
| 通知 | iOS 16.4+ 且已安装到主屏幕才支持 Web Push，且必须由服务端推送，不能本地定时 |
| 视觉 | CSS `backdrop-filter` 可做出接近的玻璃观感，性能良好 |
| 数据 | IndexedDB 本地存储；班级同步走现有后端 |
| 分发 | 免审核，链接即用，可立即迭代 |
| 工期 | **2.5–4 周**（单人） |
| 长期维护 | 独立一套前端技术栈（TS），与 Android 双轨 |

适用：想让 iPhone 用户**马上**能查课表、看班级课表。放弃桌面小组件和精准提醒。

### 4.2 方案 B：Kotlin 共享逻辑 + SwiftUI 原生界面（推荐长期方案）

**形态**：把 `domain` / `model` / `importing` / `classsync` 抽成 KMP 模块（Kotlin/Native 编译成 iOS framework），界面用 SwiftUI 从零写，材质用 iOS 26 原生 Liquid Glass。

| 维度 | 评估 |
| --- | --- |
| 交付物 | 必须在 Mac 上产出 `.ipa`，经 TestFlight 或 App Store 分发 |
| 验证能力 | **本机无法验证**，需 Mac 或 GitHub Actions `macos-15` runner 跑 `xcodebuild` + `fastlane` |
| 功能覆盖 | 全功能：WidgetKit 小组件、UNUserNotificationCenter 本地提醒、ActivityKit 实时活动、图标切换、备份导入 |
| 视觉 | 最佳。iOS 26 的 `glassEffect` 是系统级能力，效果优于 Android 侧自绘 |
| 数据 | SQLite（GRDB）或 SwiftData；**建议不做 schema 迁移链复刻**，改用备份 JSON 导入 |
| 分发 | 需 $99/年账号 + 审核（首次约 1–2 周往返） |
| 工期 | **16–22 周**（单人，含缓冲、不含审核被拒重做） |
| 长期维护 | 一套共享业务逻辑 + 两套原生 UI，是行业标准做法 |

分工拆解（单人估算，按 6h/天）：

| 阶段 | 内容 | 工期 |
| --- | --- | --- |
| 1 | KMP 工程搭建、`java.time` → `kotlinx-datetime`、共享层测试跑通 | 3–4 周 |
| 2 | 数据层（GRDB schema + 仓储 + 备份 JSON 导入） | 2–3 周 |
| 3 | 核心界面：周视图、日视图、课程编辑、多课表、设置 | 5–6 周 |
| 4 | 系统集成：通知、小组件、Live Activity、图标、导入流程 | 2–3 周 |
| 5 | 材质与动效打磨 | 2–3 周 |
| 6 | 测试、上架材料、审核往返 | 2–3 周 |

### 4.3 方案 C：Compose Multiplatform 全量迁移

**看起来最"省事"，实际最贵**，原因：

1. **UI 并不白送**。项目界面 88 个文件 / 10,980 行建立在 COUI（`com.itos.coui`）与 Miuix 上，这两个库**只有 Android 制品**，CMP 上不可用，需要整体替换（或自己重写一套跨平台材质）。
2. **玻璃材质是重灾区**。`glass/` 48 文件 6,819 行依赖 `RuntimeShader`、`RenderEffect`、`GraphicsLayer`，CMP 的 iOS 后端是 Skia，这套 API 不存在，必须改用 Skia RuntimeEffect 重写，等于重做视觉层。
3. **Room 支持 KMP，但 WorkManager 不支持**，提醒、同步、备份三条后台链路全部要写 `expect/actual` 的 iOS 实现。
4. **`java.time` 不可用于 Kotlin/Native**，79 个文件要改。
5. 小组件、Live Activity、App Intent 仍要用 Swift 写，CMP 帮不上忙。

结论：**工作量反而比方案 B 更大，且跨平台 UI 的视觉保真度低于原生**。仅在「未来还要上鸿蒙/桌面端且 UI 愿意统一降级」时才值得考虑。

### 4.4 打分对比

| 维度（1–5 分） | A. PWA | B. KMP + SwiftUI | C. CMP |
| --- | --- | --- | --- |
| 交付速度 | **5** | 2 | 1 |
| iOS 原生体验 | 3 | **5** | 3 |
| 功能完整度（小组件/通知/实时活动） | 1 | **5** | 3 |
| 现有代码复用 | 1 | 3 | 3 |
| 长期维护成本（越低越省，分越高） | **5** | 3 | 4 |
| 技术风险（越低分越高） | **4** | 3 | 2 |
| 本机可验证性 | **5** | 1 | 1 |
| **合计** | **24** | 22 | 17 |

---

## 5. 风险矩阵

| 风险 | 影响 | 概率 | 应对 |
| --- | --- | --- | --- |
| 无 Mac，原生方案无法自测 | 高 | 确定 | GitHub Actions `macos-15` / Codemagic 跑构建；本机只做业务逻辑单测 |
| App Store 审核被拒（工具类、类目、隐私清单） | 中 | 中 | 首次提交准备隐私清单、`PrivacyInfo.xcprivacy`、说明文档；准备 2 次往返预算 |
| 玻璃材质在 iOS 上还原度不符预期 | 中 | 中 | 直接使用 iOS 26 原生 `glassEffect`，不强行复刻 Android 自绘参数 |
| Room 50 版 schema 迁移链无法照搬 | 中 | 高 | iOS 端用备份 JSON 导入，不复刻迁移链 |
| `java.time` 大面积替换引入时区/跨周 bug | 中 | 中 | 复用现有 1,216 个单测的用例设计，先跑共享层测试 |
| 双端数据不一致（班级同步） | 中 | 低 | 后端为唯一真源，客户端只做展示与提交 |
| 单人开发中断导致半成品 | 高 | 中 | 分阶段可交付：阶段 2 结束即可跑只读课表版本 |

---

## 6. 分阶段建议与验收标准

### 阶段 1（建议立即启动）：PWA

| 项 | 内容 |
| --- | --- |
| 范围 | 周视图、日视图、课程增删改、多课表、`.xls` 导入、备份导出/导入、班级同步 |
| 不做 | 小组件、Live Activity、本地定时提醒（改为打开时展示"今日课程"） |
| 工时 | 2.5–4 周 |
| 验收 | ① iPhone Safari 添加到主屏幕后全屏可用；② 断网可查看已导入课表；③ 导入现有 `.xls` 得到与 Android 一致的课程数与节次；④ 班级码可同步到同一份课表；⑤ Lighthouse PWA 可安装项全绿 |
| 本机可验证 | 是（局域网 + 真机 Safari） |

### 阶段 2（用户量验证后）：KMP + SwiftUI

| 项 | 内容 |
| --- | --- |
| 前提 | 有 Mac 或 macOS CI；Apple 开发者账号；PWA 数据显示 iOS 有稳定日活 |
| 里程碑 1（6–7 周） | 共享层 + 数据层完成，能只读显示周视图 |
| 里程碑 2（13–15 周） | 编辑、导入、设置、通知、备份补齐，可 TestFlight 内测 |
| 里程碑 3（16–22 周） | 小组件 + Live Activity + 上架 |
| 验收 | ① 与 Android 版同课表数据渲染一致（逐节比对）；② 提醒触发误差 < 1 分钟；③ 小组件正确显示今日课程；④ 冷启动 < 1.5s（iPhone 12 及以上）；⑤ App Store 审核通过 |

---

## 7. 最终建议

1. **短期用 PWA 把 iPhone 可用性做出来。** 成本 2.5–4 周，不需要 Mac、不需要账号、不需要审核，可立即上线并用同一套班级同步后端。用户能查课表、能导入、能同步，已经覆盖 80% 的实际使用场景。
2. **中期用 KMP + SwiftUI 做原生版。** 前提是 PWA 数据证明 iOS 侧有真实用户。原生版的价值集中在 Android 做不到或体验差的部分：桌面小组件、精准提醒、Live Activity、以及 iOS 26 原生玻璃材质。
3. **不要走 Compose Multiplatform 全量迁移。** UI 与材质层 85% 需要重写，成本高于原生方案，视觉保真度反而更低。
4. **iOS 数据迁移走备份 JSON，不走数据库 schema。** 现有 `feature/backup` 的导出格式就是天然的跨平台通道，比复刻 48 个 Room 迁移更稳。
5. **所有原生 iOS 工期必须以拥有 Mac/CI 为前提重新核算。** 本报告中的周数为工程量级，非承诺。

---

## 附：本机能力边界核对

| 检查项 | 结果 |
| --- | --- |
| 本机操作系统 | Windows |
| 是否具备 Xcode | 否 |
| 是否具备 iOS 模拟器 | 否 |
| 能否产出可安装的 `.ipa` | 否 |
| 能否在 iPhone 上验证原生版本 | 否 |
| 能否验证 PWA | 是（浏览器 + 局域网真机） |
| 能否运行共享层 Kotlin 单测 | 是（JVM target） |

> 若确定进入阶段 2，第一件事不是写代码，而是先解决 macOS 构建环境（本地 Mac mini 或 GitHub Actions macOS runner + fastlane 签名）。
