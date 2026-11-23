# 班级课表同步后端

Cloudflare Worker + D1 实现的班级同步服务，供 SleepDown 非官方修改版的「班级课表」功能使用。

## 能力

- 管理员创建班级，得到 8 位班级号和一次性返回的管理员令牌。
- 同学用班级号加入；可选加入口令。
- 管理员把某个课表发布为快照，版本号自增。
- 同学按版本号增量拉取，未变更时返回 `notModified`。
- 管理员查看成员、修改班级信息、解散班级。
- 自带单页管理台，用一个全局密码管理服务器上的全部班级。
- 服务端不保存用户账号和设备信息，只保存昵称、加入时间、最近同步时间。

## 部署

```bash
cd server
npm install

# 登录 Cloudflare
npx wrangler login

# 创建 D1 数据库，记下返回的 database_id
npx wrangler d1 create sleepdown-class-sync

# 把 database_id 填入 wrangler.toml 后，初始化远端表结构
npm run db:remote

# 部署
npm run deploy
```

部署完成后，Wrangler 会输出类似 `https://sleepdown-class-sync.<你的子域>.workers.dev` 的地址。
把这个地址填进 App 的「班级课表 → 同步服务器地址」。

### 备选：部署到 Cloudflare Pages Functions

部分网络环境下 `*.workers.dev` 会被 DNS 污染或无法直连，此时可以把同一套 API 部署成
Cloudflare Pages Functions，并使用 `*.pages.dev` 域名。两者共用同一个 D1 数据库，数据完全互通。

```bash
cd server

# 把 src/core.js、src/d1-store.js 同步到 pages/functions/lib/
npm run sync:pages

# 首次部署会创建 Pages 项目
npm run deploy:pages
```

`server/pages/wrangler.toml` 已经绑定同一个 D1 数据库（`database_id` 与根目录 `wrangler.toml` 一致）。
部署完成后会得到类似 `https://sleepdown-class-sync-pages.pages.dev` 的地址。

> 本项目实际使用的线上地址：`https://sleepdown-class-sync-pages.pages.dev`
>
> - 健康检查：`GET /api/v1/health`
> - `*.workers.dev` 在当前网络不可达，因此 App 里应填写 Pages 地址。

本地联调：

```bash
npm run dev        # http://127.0.0.1:8787，数据在内存中
npm test           # 纯 Node 测试，不需要 Cloudflare 账号
```

## 管理台

服务端自带一个单页管理界面，用来查看和管理服务器上所有已上传的班级课表，不需要安装任何东西。

### 启用

管理台用一把全局密码（`ADMIN_KEY`）保护，需要在部署环境里设置一次：

```bash
cd server
npx wrangler secret put ADMIN_KEY      # Worker 部署
# Pages 部署则：
cd pages && npx wrangler pages secret put ADMIN_KEY --project-name sleepdown-class-sync-pages
```

未设置 `ADMIN_KEY` 时，管理台登录接口返回 `503 admin_not_configured`，其他业务接口不受影响。

本地开发时默认密码是 `dev-admin`，也可以用环境变量覆盖：

```bash
ADMIN_KEY=my-password npm run dev
```

### 入口

| 部署方式 | 管理台地址 |
| --- | --- |
| Worker | `https://<你的域名>/admin` |
| Pages Functions | `https://sleepdown-class-sync-pages.pages.dev/admin.html` |
| 本地 | `http://127.0.0.1:8787/admin` |

登录后得到一枚短期会话令牌（默认 12 小时，可用 `ADMIN_SESSION_HOURS` 调整），保存在浏览器 `sessionStorage`，关掉标签页即失效。

### 能做什么

- 浏览全部班级：班级号、班级名、老师、是否设了加入口令、成员数、课表版本与体积、最后更新时间。
- 搜索班级号／班级名／老师，按「全部 / 已发布 / 未发布 / 有加入口令」筛选，按创建时间、更新时间、成员数排序。
- 查看单个班级详情：成员列表、已发布课表摘要（课程数、时间段数、总周数、学期开始日）。
- 修改班级名、老师、加入口令（留空即清除口令）。
- 导出该班级的课表 JSON。
- 删除班级（需手动输入班级号确认）。

管理界面只展示统计信息，不会返回 `adminToken` 哈希或加入口令哈希。

## 接口

所有接口返回 JSON，时间戳单位为毫秒。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/health` | 健康检查 |
| POST | `/api/v1/classes` | 创建班级 |
| GET | `/api/v1/classes/{code}` | 班级公开信息 |
| PATCH | `/api/v1/classes/{code}` | 管理员修改班级 |
| DELETE | `/api/v1/classes/{code}` | 管理员解散班级 |
| POST | `/api/v1/classes/{code}/join` | 加入班级，返回最新课表 |
| GET | `/api/v1/classes/{code}/schedule` | 拉取课表，可带 `?version=` |
| PUT | `/api/v1/classes/{code}/schedule` | 管理员发布课表 |
| GET | `/api/v1/classes/{code}/admin` | 管理员查看成员 |
| POST | `/api/v1/admin/login` | 管理台登录，返回短期会话令牌 |
| GET | `/api/v1/admin/classes` | 管理台列出全部班级 |
| GET | `/api/v1/admin/classes/{code}` | 管理台查看班级详情与成员 |
| PATCH | `/api/v1/admin/classes/{code}` | 管理台修改班级信息 |
| DELETE | `/api/v1/admin/classes/{code}` | 管理台解散班级 |
| GET | `/api/v1/admin/classes/{code}/schedule` | 管理台导出课表快照 |

管理员接口使用 `Authorization: Bearer <adminToken>`；`/api/v1/admin/*` 使用登录得到的会话令牌（同样放在 `Authorization: Bearer` 里）。

### 创建班级

```http
POST /api/v1/classes
Content-Type: application/json

{
  "name": "临床医学 2024-1 班",
  "teacher": "李老师",
  "joinPassword": "1234",
  "nickname": "李老师"
}
```

响应：

```json
{
  "code": "ABCD2345",
  "displayCode": "ABCD-2345",
  "adminToken": "……",
  "class": {
    "code": "ABCD2345",
    "displayCode": "ABCD-2345",
    "name": "临床医学 2024-1 班",
    "teacher": "李老师",
    "joinPasswordRequired": true,
    "memberCount": 1,
    "scheduleVersion": 0,
    "scheduleUpdatedAt": null
  }
}
```

`adminToken` 只返回一次，App 保存在本机。请勿清理应用数据，否则需要重新创建班级。

### 发布课表

```http
PUT /api/v1/classes/ABCD2345/schedule
Authorization: Bearer <adminToken>
Content-Type: application/json

{
  "payload": "{...SleepDown 规范化课表 JSON...}",
  "expectedVersion": 3
}
```

`payload` 必须包含 `courses` 数组，大小不超过 512 KB。`expectedVersion` 可省略；填写时若版本不一致会返回 `409`。

### 拉取课表

```http
GET /api/v1/classes/ABCD2345/schedule?version=3
```

如果 `version` 与当前版本一致，返回：

```json
{ "notModified": true, "version": 3 }
```

否则返回 `class` 和 `schedule`（`version`、`payload`、`updatedAt`）。

## 数据表

见 `schema.sql`：

- `classes`：班级信息、加入口令哈希、管理员令牌哈希、最新课表快照与版本号。
- `members`：班级成员昵称、角色、加入与最近同步时间。

## 安全说明

- 班级号是访问课表的凭证；带加入口令时口令使用 PBKDF2-SHA256 存储。
- 管理员令牌只保存 SHA-256 哈希，服务端无法还原明文。
- 管理台的 `ADMIN_KEY` 以 Cloudflare Secret 形式保存，不进代码库；登录后发放 HMAC-SHA256 签名的短期会话令牌，密码比较使用定时安全等长比较。
- 这是一个轻量同步服务，没有账号体系，也没有内置频控；公开部署时建议在 Cloudflare 控制台为 `/api/v1/classes` 配置 Rate limiting 规则。
- 课表内容会以明文保存在 D1 中。如果课表包含敏感信息，请使用私有部署或自行增加字段级加密。