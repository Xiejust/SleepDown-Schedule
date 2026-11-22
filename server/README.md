# 班级课表同步后端

Cloudflare Worker + D1 实现的班级同步服务，供 SleepDown 非官方修改版的「班级课表」功能使用。

## 能力

- 管理员创建班级，得到 8 位班级号和一次性返回的管理员令牌。
- 同学用班级号加入；可选加入口令。
- 管理员把某个课表发布为快照，版本号自增。
- 同学按版本号增量拉取，未变更时返回 `notModified`。
- 管理员查看成员、修改班级信息、解散班级。
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

管理员接口使用 `Authorization: Bearer <adminToken>`。

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
- 这是一个轻量同步服务，没有账号体系，也没有内置频控；公开部署时建议在 Cloudflare 控制台为 `/api/v1/classes` 配置 Rate limiting 规则。
- 课表内容会以明文保存在 D1 中。如果课表包含敏感信息，请使用私有部署或自行增加字段级加密。