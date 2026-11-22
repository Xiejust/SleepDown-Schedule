# 班级课表同步

> 本功能是本仓库（`Xiejust/SleepDown-Schedule`）在原项目 [SleepDown-Schedule](https://github.com/xiaomanjun233/SleepDown-Schedule) 基础上新增的**非官方修改版**功能，与原作者的官方版本无关。原项目采用源码可见的非商业许可，详见仓库根目录 `LICENSE.md`。

班级课表同步解决的问题是：一个班只需要一个人维护课表，其他人加入班级后自动拿到同一份课表，管理员改完课堂安排后，其他同学不用手动导入。

## 角色与流程

### 管理员

1. 在「设置 → 班级课表」填写同步服务器地址，创建班级，设置班级名、老师、可选的加入口令和自己的昵称。
2. 服务端返回 8 位班级号和**只返回一次**的管理员令牌，App 把令牌保存在本机。
3. 在管理面板选择一个本地课表，点击发布。发布内容是该课表的 SleepDown 规范化 JSON，服务端保存为版本 `1` 的快照。
4. 打开「自动发布」后，App 在课表内容变化后（退到后台、下次启动或后台周期任务）计算内容哈希，与上次发布不同才上传新版本。
5. 可以在管理面板查看成员列表、最近同步时间，也可以修改班级信息或解散班级。

### 同学

1. 填写同一个服务器地址和班级号，按需输入加入口令和昵称。
2. 加入成功后立即拉取当前快照，并在本地新建一个名为「班级 · 班级名」的独立课表。
3. 之后 App 回到前台或后台周期任务发现版本变化时，只覆盖这张班级课表；本机已有的其他课表、设置不受影响。
4. 可以随时退出班级。退出只解除本地绑定，不会删除服务端班级，也不会删除已经同步到本地的课表。

## 同步机制

- **版本号**：每次发布成功版本号 +1；同学拉取时带上本地已知版本，服务端版本一致时返回 `notModified`，不重复传输课表。
- **数据格式**：直接复用应用现有的规范化课表 JSON（`CanonicalScheduleJson` / `buildCanonicalScheduleJson`），因此课程、节次、开学日期、总周数、作息方案和多课表目录信息与本地导入保持一致，不需要新的转换层。
- **写入方式**：已绑定且仍然存在的班级课表原地覆盖；课表被删除或首次加入时新建一张课表并命名为「班级 · 班级名」。覆盖走 `ScheduleRepository.importDraftForSchedule`，与导入预览确认后的写入路径相同。
- **触发时机**：
  - 同学：App 进程回到前台（`ProcessLifecycleOwner.onStart`）时拉取一次；WorkManager 唯一周期任务每 15 分钟在联网条件下检查一次。
  - 管理员：开启自动发布后，同样在退到后台、前台启动和周期任务时检查课表内容哈希，有变化才发布。
- **离线与失败**：网络失败不会阻塞 App 启动，也不会清空本地课表；后台任务失败最多重试两次后放弃，等待下一次周期触发。

## 配置项

设置保存在应用私有 SharedPreferences `class_sync_settings` 中：

| 字段 | 说明 |
| --- | --- |
| `serverUrl` | 自部署同步服务地址 |
| `classCode` | 8 位班级号 |
| `className` / `teacher` | 班级信息缓存 |
| `role` | `admin` 或 `member` |
| `adminToken` | 管理员令牌，仅管理端保存 |
| `memberId` | 同学在本班的成员 ID |
| `nickname` | 班级内昵称 |
| `linkedScheduleId` | 绑定的本地课表 ID |
| `lastVersion` | 已同步或已发布的版本号 |
| `lastSyncedAt` | 最近一次成功同步时间 |
| `autoPublish` | 管理端是否自动发布 |
| `lastPublishedHash` | 上次发布内容的 SHA-256，用于避免重复发布 |

## 代码位置

| 路径 | 作用 |
| --- | --- |
| `app/.../feature/classsync/ClassSyncModels.kt` | API DTO、设置模型、异常类型 |
| `app/.../feature/classsync/ClassSyncStore.kt` | SharedPreferences 持久化 |
| `app/.../feature/classsync/ClassSyncClient.kt` | `HttpURLConnection` API 客户端 |
| `app/.../feature/classsync/ClassSyncManager.kt` | 创建/加入/同步/发布/退出等业务逻辑 |
| `app/.../feature/classsync/ClassSyncWorker.kt` | WorkManager 周期同步 |
| `app/.../feature/classsync/ClassSyncActivity.kt` | Compose 界面 |
| `server/` | Cloudflare Worker + D1 后端、测试与部署文档 |

应用入口：

- `CourseScheduleApp` 在进程前后台切换时触发同步与自动发布。
- `SettingsRootScreen` 的「班级课表」入口打开 `ClassSyncActivity`。
- `AndroidManifest.xml` 注册 `ClassSyncActivity`。

## 服务端

后端是独立的 Cloudflare Worker，数据库使用 D1。部署、建表、接口示例和安全说明见 [`server/README.md`](../server/README.md)。

核心接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/v1/classes` | 创建班级，返回班级号和管理员令牌 |
| `POST` | `/api/v1/classes/{code}/join` | 加入班级，返回最新课表 |
| `GET` | `/api/v1/classes/{code}/schedule?version=N` | 拉取课表，可增量判断 |
| `PUT` | `/api/v1/classes/{code}/schedule` | 管理员发布课表 |
| `GET` | `/api/v1/classes/{code}/admin` | 管理员查看成员 |
| `PATCH` / `DELETE` | `/api/v1/classes/{code}` | 管理员修改或解散班级 |

管理员接口使用 `Authorization: Bearer <adminToken>`。服务端只保存管理员令牌的 SHA-256 哈希和加入口令的 PBKDF2 哈希，不保存明文。

## 安全与隐私

- 班级号本身就是读取课表的凭证；需要保密时请同时设置加入口令。
- 课表以明文存储在 D1 中。涉及敏感信息时请私有部署，或自行增加字段级加密。
- 服务端不收集账号、设备标识和位置；成员表只保存昵称、角色、加入时间和最近同步时间。
- 公开部署时建议在 Cloudflare 控制台为创建班级等写接口配置速率限制。
- 管理员令牌只存在管理员的设备上；清除应用数据会导致无法再管理原班级，需要重新创建。

## 已知限制

- 同步依赖用户自行部署的服务端，仓库不内置公共服务器。
- Android 后台任务受系统省电策略影响，周期同步不保证精确到 15 分钟；打开 App 时会立即补一次同步。
- 当前不支持多管理员、历史版本回滚、课表合并或冲突合并；发布采用"后写覆盖"，管理员发布前请确认当前课表内容。
- 同学本地对班级课表的手动修改会在下一次同步时被覆盖，这是预期行为。
- 本仓库未附带构建好的 APK；本地构建需要 Android SDK 和 JDK，后端测试可直接用 Node 运行（见 `server/README.md`）。