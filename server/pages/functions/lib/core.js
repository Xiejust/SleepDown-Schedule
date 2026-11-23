/**
 * 班级课表同步 API 核心逻辑。
 *
 * 这一层不依赖 Cloudflare，只依赖一个实现 Store 接口的对象，
 * 因此可以在 Node 里用内存实现跑测试，在 Worker 里用 D1 实现跑线上。
 */

const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const CODE_LENGTH = 8;
const MAX_BODY_BYTES = 512 * 1024;
const MAX_PAYLOAD_BYTES = 512 * 1024;
const PBKDF2_ITERATIONS = 10_000;
const MAX_NAME = 40;
const MAX_NICKNAME = 24;

const textEncoder = new TextEncoder();

function cryptoApi() {
  return globalThis.crypto ?? globalThis.__sleepdownWebcrypto;
}

function base64Url(bytes) {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function randomBytes(length) {
  return cryptoApi().getRandomValues(new Uint8Array(length));
}

export function randomToken() {
  return base64Url(randomBytes(32));
}

export function randomMemberId() {
  return base64Url(randomBytes(12));
}

async function sha256Hex(value) {
  const digest = await cryptoApi().subtle.digest('SHA-256', textEncoder.encode(value));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

async function passwordHash(password, salt) {
  const key = await cryptoApi().subtle.importKey(
    'raw', textEncoder.encode(password), 'PBKDF2', false, ['deriveBits']
  );
  const bits = await cryptoApi().subtle.deriveBits(
    { name: 'PBKDF2', salt: textEncoder.encode(salt), iterations: PBKDF2_ITERATIONS, hash: 'SHA-256' },
    key, 256
  );
  return [...new Uint8Array(bits)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

export function normalizeCode(input) {
  if (typeof input !== 'string') return null;
  const code = input.toUpperCase().replace(/[\s-]/g, '');
  if (code.length !== CODE_LENGTH) return null;
  if (![...code].every((c) => CODE_ALPHABET.includes(c))) return null;
  return code;
}

export function displayCode(code) {
  return `${code.slice(0, 4)}-${code.slice(4)}`;
}

export function newClassCode() {
  const bytes = randomBytes(CODE_LENGTH);
  let code = '';
  for (let i = 0; i < CODE_LENGTH; i += 1) code += CODE_ALPHABET[bytes[i] % CODE_ALPHABET.length];
  return code;
}

function json(status, body, extraHeaders = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
      ...extraHeaders,
    },
  });
}

function empty(status, extraHeaders = {}) {
  return new Response(null, { status, headers: extraHeaders });
}

function corsHeaders() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, PUT, PATCH, DELETE, OPTIONS',
    'Access-Control-Allow-Headers': 'Authorization, Content-Type',
    'Access-Control-Max-Age': '86400',
  };
}

async function readJson(request) {
  const contentLength = Number(request.headers.get('content-length') ?? 0);
  if (contentLength > MAX_BODY_BYTES) {
    return { error: json(413, { error: 'payload_too_large', message: '请求体过大' }, corsHeaders()) };
  }
  const text = await request.text();
  if (text.length > MAX_BODY_BYTES) {
    return { error: json(413, { error: 'payload_too_large', message: '请求体过大' }, corsHeaders()) };
  }
  if (!text.trim()) return { value: {} };
  try {
    return { value: JSON.parse(text) };
  } catch {
    return { error: json(400, { error: 'invalid_json', message: '请求体不是合法 JSON' }, corsHeaders()) };
  }
}

function cleanString(value, maxLength) {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  if (!trimmed || trimmed.length > maxLength) return null;
  return trimmed;
}

async function hashAdminToken(token) {
  return sha256Hex(`sleepdown-class-admin:${token}`);
}

function bearerToken(request) {
  const header = request.headers.get('authorization') ?? '';
  const match = /^Bearer\s+(.+)$/i.exec(header.trim());
  return match ? match[1].trim() : null;
}

async function requireAdmin(request, store, code) {
  const token = bearerToken(request);
  if (!token) return { error: json(401, { error: 'admin_token_required', message: '需要管理员令牌' }, corsHeaders()) };
  const record = await store.getClass(code);
  if (!record) return { error: json(404, { error: 'class_not_found', message: '班级不存在' }, corsHeaders()) };
  const hash = await hashAdminToken(token);
  if (hash !== record.adminTokenHash) {
    return { error: json(403, { error: 'admin_token_invalid', message: '管理员令牌无效' }, corsHeaders()) };
  }
  return { record };
}

/* ---------------------------------------------------------------- 管理台 */

const ADMIN_SESSION_HOURS_DEFAULT = 12;

function adminKey(env) {
  const key = typeof env?.ADMIN_KEY === 'string' ? env.ADMIN_KEY.trim() : '';
  return key || null;
}

function timingSafeEqual(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

async function hmacBase64Url(secret, message) {
  const key = await cryptoApi().subtle.importKey(
    'raw', textEncoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']
  );
  const signature = await cryptoApi().subtle.sign('HMAC', key, textEncoder.encode(message));
  return base64Url(new Uint8Array(signature));
}

function adminSessionSeconds(env) {
  const hours = Number(env?.ADMIN_SESSION_HOURS ?? ADMIN_SESSION_HOURS_DEFAULT);
  const safe = Number.isFinite(hours) && hours > 0 && hours <= 24 * 30 ? hours : ADMIN_SESSION_HOURS_DEFAULT;
  return Math.round(safe * 3600);
}

async function issueAdminToken(env) {
  const expiresAt = Date.now() + adminSessionSeconds(env) * 1000;
  const signature = await hmacBase64Url(adminKey(env), `sleepdown-admin:${expiresAt}`);
  return { token: `${expiresAt}.${signature}`, expiresAt };
}

/** 管理台使用服务端统一密钥换取的短期会话令牌，与单个班级的管理员令牌互不相干。 */
async function requireGlobalAdmin(request, env) {
  const key = adminKey(env);
  if (!key) {
    return {
      error: json(503, {
        error: 'admin_not_configured',
        message: '服务端未配置 ADMIN_KEY，管理台暂不可用',
      }, corsHeaders()),
    };
  }
  const token = bearerToken(request);
  if (!token) {
    return { error: json(401, { error: 'admin_token_required', message: '请先登录管理台' }, corsHeaders()) };
  }
  const separator = token.indexOf('.');
  if (separator <= 0) {
    return { error: json(401, { error: 'admin_token_invalid', message: '登录状态无效，请重新登录' }, corsHeaders()) };
  }
  const expiresAt = Number(token.slice(0, separator));
  const signature = token.slice(separator + 1);
  if (!Number.isFinite(expiresAt) || !signature) {
    return { error: json(401, { error: 'admin_token_invalid', message: '登录状态无效，请重新登录' }, corsHeaders()) };
  }
  if (expiresAt <= Date.now()) {
    return { error: json(401, { error: 'admin_session_expired', message: '登录已过期，请重新登录' }, corsHeaders()) };
  }
  const expected = await hmacBase64Url(key, `sleepdown-admin:${expiresAt}`);
  if (!timingSafeEqual(expected, signature)) {
    return { error: json(401, { error: 'admin_token_invalid', message: '登录状态无效，请重新登录' }, corsHeaders()) };
  }
  return { ok: true };
}

function byteLength(value) {
  return new TextEncoder().encode(value).length;
}

function summarizePayload(payload) {
  const summary = { bytes: byteLength(payload), courses: 0, timeSlots: 0, totalWeeks: null, termStartDate: null };
  try {
    const parsed = JSON.parse(payload);
    summary.courses = Array.isArray(parsed?.courses) ? parsed.courses.length : 0;
    const slots = parsed?.timeSlots ?? parsed?.periods;
    summary.timeSlots = Array.isArray(slots) ? slots.length : 0;
    const config = parsed?.config ?? parsed?.schedule ?? null;
    const weeks = Number(parsed?.totalWeeks ?? config?.totalWeeks);
    if (Number.isFinite(weeks) && weeks > 0) summary.totalWeeks = weeks;
    const start = parsed?.termStartDate ?? config?.termStartDate;
    if (typeof start === 'string' && start) summary.termStartDate = start;
  } catch {
    summary.parseError = true;
  }
  return summary;
}

function adminClassSummary(record) {
  const payloadBytes = record.payloadBytes ?? 0;
  return {
    code: record.code,
    displayCode: displayCode(record.code),
    name: record.name,
    teacher: record.teacher ?? '',
    joinPasswordRequired: Boolean(record.joinPasswordHash),
    memberCount: record.memberCount ?? 0,
    scheduleVersion: record.scheduleVersion ?? 0,
    scheduleUpdatedAt: record.scheduleUpdatedAt ?? null,
    hasSchedule: record.hasSchedule ?? payloadBytes > 0,
    payloadBytes,
    lastSeenAt: record.lastSeenAt ?? null,
    createdAt: record.createdAt ?? null,
    updatedAt: record.updatedAt ?? null,
  };
}

function publicClass(record, includeScheduleVersion = true) {
  return {
    code: record.code,
    displayCode: displayCode(record.code),
    name: record.name,
    teacher: record.teacher ?? '',
    joinPasswordRequired: Boolean(record.joinPasswordHash),
    memberCount: record.memberCount ?? 0,
    ...(includeScheduleVersion
      ? {
          scheduleVersion: record.scheduleVersion,
          scheduleUpdatedAt: record.scheduleUpdatedAt ?? null,
        }
      : {}),
  };
}

function scheduleBody(record) {
  if (!record.schedulePayload) return null;
  return {
    version: record.scheduleVersion,
    payload: record.schedulePayload,
    updatedAt: record.scheduleUpdatedAt,
  };
}

function validatePayload(payload) {
  if (typeof payload !== 'string' || !payload.trim()) return '课表内容不能为空';
  if (new TextEncoder().encode(payload).length > MAX_PAYLOAD_BYTES) return '课表内容过大';
  let parsed;
  try {
    parsed = JSON.parse(payload);
  } catch {
    return '课表内容不是合法 JSON';
  }
  if (!parsed || typeof parsed !== 'object' || !Array.isArray(parsed.courses)) {
    return '课表内容缺少 courses 数组';
  }
  return null;
}

/**
 * Store 接口（全部 async）：
 *   getClass(code) -> record | null
 *   createClass(record) -> void
 *   updateClass(code, patch) -> void
 *   deleteClass(code) -> void
 *   upsertMember(code, member) -> void
 *   listMembers(code) -> member[]
 */
export async function handleRequest(request, store, env = {}) {
  const url = new URL(request.url);
  const path = url.pathname.replace(/\/+$/, '') || '/';

  if (request.method === 'OPTIONS') return empty(204, corsHeaders());
  if (path === '/' || path === '/api/v1/health') {
    return json(200, { ok: true, service: 'sleepdown-class-sync', version: 1 }, corsHeaders());
  }

  const parts = path.split('/').filter(Boolean);
  if (parts[0] === 'api' && parts[1] === 'v1' && parts[2] === 'admin') {
    return handleAdminRequest(request, store, env, parts.slice(3));
  }
  // /api/v1/classes[/:code[/schedule|admin|join]]
  if (parts[0] !== 'api' || parts[1] !== 'v1' || parts[2] !== 'classes') {
    return json(404, { error: 'not_found', message: '接口不存在' }, corsHeaders());
  }

  if (parts.length === 3) {
    if (request.method === 'POST') return createClass(request, store);
    return json(405, { error: 'method_not_allowed', message: '请使用 POST' }, corsHeaders());
  }

  const code = normalizeCode(parts[3]);
  if (!code) return json(404, { error: 'class_not_found', message: '班级不存在' }, corsHeaders());
  const action = parts[4] ?? '';
  if (parts.length > 5) return json(404, { error: 'not_found', message: '接口不存在' }, corsHeaders());

  if (action === 'join' && request.method === 'POST') return joinClass(request, store, code);
  if (action === 'schedule') {
    if (request.method === 'GET') return fetchSchedule(request, store, code, url);
    if (request.method === 'PUT' || request.method === 'POST') return publishSchedule(request, store, code);
    return json(405, { error: 'method_not_allowed', message: '不支持的方法' }, corsHeaders());
  }
  if (action === 'admin' && request.method === 'GET') return adminOverview(request, store, code);
  if (action === '' && request.method === 'GET') return classInfo(store, code);
  if (action === '' && request.method === 'PATCH') return updateClass(request, store, code);
  if (action === '' && request.method === 'DELETE') return deleteClass(request, store, code);

  return json(404, { error: 'not_found', message: '接口不存在' }, corsHeaders());
}

async function createClass(request, store) {
  const parsed = await readJson(request);
  if (parsed.error) return parsed.error;
  const body = parsed.value ?? {};
  const name = cleanString(body.name, MAX_NAME);
  if (!name) return json(400, { error: 'invalid_name', message: '班级名称需为 1-40 个字符' }, corsHeaders());
  const teacher = body.teacher == null ? '' : cleanString(body.teacher, MAX_NAME);
  if (teacher === null) return json(400, { error: 'invalid_teacher', message: '教师名称过长' }, corsHeaders());
  const nickname = body.nickname == null ? '管理员' : cleanString(body.nickname, MAX_NICKNAME);
  if (nickname === null) return json(400, { error: 'invalid_nickname', message: '昵称过长' }, corsHeaders());

  let joinPasswordHash = null;
  let joinPasswordSalt = null;
  if (body.joinPassword != null && body.joinPassword !== '') {
    const password = cleanString(body.joinPassword, 64);
    if (!password || password.length < 4) {
      return json(400, { error: 'invalid_join_password', message: '加入口令至少 4 个字符' }, corsHeaders());
    }
    joinPasswordSalt = base64Url(randomBytes(12));
    joinPasswordHash = await passwordHash(password, joinPasswordSalt);
  }

  let code = null;
  for (let attempt = 0; attempt < 8 && !code; attempt += 1) {
    const candidate = newClassCode();
    if (!(await store.getClass(candidate))) code = candidate;
  }
  if (!code) return json(503, { error: 'code_exhausted', message: '班级号生成失败，请重试' }, corsHeaders());

  const adminToken = randomToken();
  const now = Date.now();
  const record = {
    code,
    name,
    teacher,
    joinPasswordHash,
    joinPasswordSalt,
    adminTokenHash: await hashAdminToken(adminToken),
    scheduleVersion: 0,
    schedulePayload: null,
    scheduleUpdatedAt: null,
    createdAt: now,
    updatedAt: now,
  };
  await store.createClass(record);
  await store.upsertMember(code, {
    memberId: randomMemberId(),
    nickname,
    role: 'admin',
    joinedAt: now,
    lastSeenAt: now,
  });
  return json(201, {
    code,
    displayCode: displayCode(code),
    adminToken,
    class: publicClass({ ...record, memberCount: 1 }),
  }, corsHeaders());
}

async function joinClass(request, store, code) {
  const parsed = await readJson(request);
  if (parsed.error) return parsed.error;
  const body = parsed.value ?? {};
  const record = await store.getClass(code);
  if (!record) return json(404, { error: 'class_not_found', message: '班级不存在，请核对班级号' }, corsHeaders());

  if (record.joinPasswordHash) {
    const password = typeof body.password === 'string' ? body.password : '';
    const hash = await passwordHash(password, record.joinPasswordSalt);
    if (hash !== record.joinPasswordHash) {
      return json(403, { error: 'join_password_invalid', message: '加入口令不正确' }, corsHeaders());
    }
  }

  const nickname = body.nickname == null ? '同学' : cleanString(body.nickname, MAX_NICKNAME);
  if (nickname === null) return json(400, { error: 'invalid_nickname', message: '昵称过长' }, corsHeaders());
  const memberId = typeof body.memberId === 'string' && body.memberId.length <= 64 && body.memberId
    ? body.memberId
    : randomMemberId();
  const now = Date.now();
  await store.upsertMember(code, {
    memberId,
    nickname,
    role: 'member',
    joinedAt: now,
    lastSeenAt: now,
  });
  const members = await store.listMembers(code);
  return json(200, {
    memberId,
    class: publicClass({ ...record, memberCount: members.length }),
    schedule: scheduleBody(record),
  }, corsHeaders());
}

async function classInfo(store, code) {
  const record = await store.getClass(code);
  if (!record) return json(404, { error: 'class_not_found', message: '班级不存在' }, corsHeaders());
  const members = await store.listMembers(code);
  return json(200, { class: publicClass({ ...record, memberCount: members.length }) }, corsHeaders());
}

async function fetchSchedule(request, store, code, url) {
  const record = await store.getClass(code);
  if (!record) return json(404, { error: 'class_not_found', message: '班级不存在' }, corsHeaders());
  const memberId = url.searchParams.get('memberId');
  if (memberId && memberId.length <= 64) {
    await store.touchMember(code, memberId, Date.now());
  }
  const since = Number(url.searchParams.get('version') ?? '');
  if (Number.isFinite(since) && since > 0 && since === record.scheduleVersion) {
    return json(200, { notModified: true, version: record.scheduleVersion }, corsHeaders());
  }
  const members = await store.listMembers(code);
  return json(200, {
    class: publicClass({ ...record, memberCount: members.length }),
    schedule: scheduleBody(record),
  }, corsHeaders());
}

async function publishSchedule(request, store, code) {
  const auth = await requireAdmin(request, store, code);
  if (auth.error) return auth.error;
  const parsed = await readJson(request);
  if (parsed.error) return parsed.error;
  const body = parsed.value ?? {};
  const invalid = validatePayload(body.payload);
  if (invalid) return json(400, { error: 'invalid_payload', message: invalid }, corsHeaders());
  const expectedVersion = Number.isFinite(Number(body.expectedVersion)) ? Number(body.expectedVersion) : null;
  if (expectedVersion != null && expectedVersion !== auth.record.scheduleVersion) {
    return json(409, {
      error: 'version_conflict',
      message: '课表已被其他设备更新，请刷新后重试',
      currentVersion: auth.record.scheduleVersion,
    }, corsHeaders());
  }
  const now = Date.now();
  const nextVersion = auth.record.scheduleVersion + 1;
  await store.updateClass(code, {
    scheduleVersion: nextVersion,
    schedulePayload: body.payload,
    scheduleUpdatedAt: now,
    updatedAt: now,
  });
  const members = await store.listMembers(code);
  return json(200, {
    version: nextVersion,
    updatedAt: now,
    class: publicClass({
      ...auth.record,
      scheduleVersion: nextVersion,
      scheduleUpdatedAt: now,
      memberCount: members.length,
    }),
  }, corsHeaders());
}

async function adminOverview(request, store, code) {
  const auth = await requireAdmin(request, store, code);
  if (auth.error) return auth.error;
  const members = await store.listMembers(code);
  return json(200, {
    class: publicClass({ ...auth.record, memberCount: members.length }),
    members: members.map((m) => ({
      memberId: m.memberId,
      nickname: m.nickname,
      role: m.role,
      joinedAt: m.joinedAt,
      lastSeenAt: m.lastSeenAt,
    })),
  }, corsHeaders());
}

async function updateClass(request, store, code) {
  const auth = await requireAdmin(request, store, code);
  if (auth.error) return auth.error;
  const parsed = await readJson(request);
  if (parsed.error) return parsed.error;
  const body = parsed.value ?? {};
  const patch = { updatedAt: Date.now() };
  if (body.name != null) {
    const name = cleanString(body.name, MAX_NAME);
    if (!name) return json(400, { error: 'invalid_name', message: '班级名称需为 1-40 个字符' }, corsHeaders());
    patch.name = name;
  }
  if (body.teacher != null) {
    const teacher = cleanString(body.teacher, MAX_NAME);
    if (teacher === null) return json(400, { error: 'invalid_teacher', message: '教师名称过长' }, corsHeaders());
    patch.teacher = teacher;
  }
  if (body.joinPassword != null) {
    if (body.joinPassword === '') {
      patch.joinPasswordHash = null;
      patch.joinPasswordSalt = null;
    } else {
      const password = cleanString(body.joinPassword, 64);
      if (!password || password.length < 4) {
        return json(400, { error: 'invalid_join_password', message: '加入口令至少 4 个字符' }, corsHeaders());
      }
      patch.joinPasswordSalt = base64Url(randomBytes(12));
      patch.joinPasswordHash = await passwordHash(password, patch.joinPasswordSalt);
    }
  }
  await store.updateClass(code, patch);
  const updated = await store.getClass(code);
  const members = await store.listMembers(code);
  return json(200, { class: publicClass({ ...updated, memberCount: members.length }) }, corsHeaders());
}

async function deleteClass(request, store, code) {
  const auth = await requireAdmin(request, store, code);
  if (auth.error) return auth.error;
  await store.deleteClass(code);
  return json(200, { deleted: true }, corsHeaders());
}
/* ------------------------------------------------------- 管理台接口实现 */

async function handleAdminRequest(request, store, env, rest) {
  if (rest.length === 1 && rest[0] === 'login') {
    if (request.method !== 'POST') {
      return json(405, { error: 'method_not_allowed', message: '请使用 POST 登录' }, corsHeaders());
    }
    return adminLogin(request, env);
  }

  const auth = await requireGlobalAdmin(request, env);
  if (auth.error) return auth.error;

  if (rest.length === 1 && rest[0] === 'classes') {
    if (request.method !== 'GET') {
      return json(405, { error: 'method_not_allowed', message: '请使用 GET' }, corsHeaders());
    }
    const classes = await store.listClasses();
    return json(200, {
      classes: classes.map(adminClassSummary),
      total: classes.length,
      serverTime: Date.now(),
    }, corsHeaders());
  }

  if (rest[0] === 'classes' && rest.length >= 2) {
    const code = normalizeCode(rest[1]);
    if (!code) return json(404, { error: 'class_not_found', message: '班级不存在' }, corsHeaders());
    const action = rest[2] ?? '';
    if (rest.length > 3) {
      return json(404, { error: 'not_found', message: '接口不存在' }, corsHeaders());
    }
    if (action === '') {
      if (request.method === 'GET') return adminClassDetail(store, code);
      if (request.method === 'PATCH') return adminUpdateClass(request, store, code);
      if (request.method === 'DELETE') return adminDeleteClass(store, code);
      return json(405, { error: 'method_not_allowed', message: '不支持的方法' }, corsHeaders());
    }
    if (action === 'schedule' && request.method === 'GET') return adminClassSchedule(store, code);
  }

  return json(404, { error: 'not_found', message: '接口不存在' }, corsHeaders());
}

async function adminLogin(request, env) {
  const key = adminKey(env);
  if (!key) {
    return json(503, {
      error: 'admin_not_configured',
      message: '服务端未配置 ADMIN_KEY，请先执行 wrangler secret put ADMIN_KEY',
    }, corsHeaders());
  }
  const parsed = await readJson(request);
  if (parsed.error) return parsed.error;
  const password = typeof parsed.value?.password === 'string' ? parsed.value.password : '';
  // 先各自哈希再比较，避免长度差异导致的时序侧信道。
  const [provided, expected] = await Promise.all([
    sha256Hex(`sleepdown-admin-login:${password}`),
    sha256Hex(`sleepdown-admin-login:${key}`),
  ]);
  if (!timingSafeEqual(provided, expected)) {
    return json(401, { error: 'admin_password_invalid', message: '管理员密码不正确' }, corsHeaders());
  }
  const session = await issueAdminToken(env);
  return json(200, {
    token: session.token,
    expiresAt: session.expiresAt,
    ttlSeconds: adminSessionSeconds(env),
  }, corsHeaders());
}

async function adminClassDetail(store, code) {
  const record = await store.getClass(code);
  if (!record) return json(404, { error: 'class_not_found', message: '班级不存在' }, corsHeaders());
  const members = await store.listMembers(code);
  const payload = record.schedulePayload ?? null;
  return json(200, {
    class: adminClassSummary({
      ...record,
      memberCount: members.length,
      payloadBytes: payload ? byteLength(payload) : 0,
      hasSchedule: Boolean(payload),
      lastSeenAt: members.reduce((latest, member) => Math.max(latest, member.lastSeenAt ?? 0), 0) || null,
    }),
    members: members.map((member) => ({
      memberId: member.memberId,
      nickname: member.nickname,
      role: member.role,
      joinedAt: member.joinedAt,
      lastSeenAt: member.lastSeenAt,
    })),
    schedule: payload ? summarizePayload(payload) : null,
  }, corsHeaders());
}

async function adminClassSchedule(store, code) {
  const record = await store.getClass(code);
  if (!record) return json(404, { error: 'class_not_found', message: '班级不存在' }, corsHeaders());
  if (!record.schedulePayload) {
    return json(404, { error: 'schedule_missing', message: '该班级还没有发布过课表' }, corsHeaders());
  }
  return json(200, {
    code: record.code,
    name: record.name,
    version: record.scheduleVersion,
    updatedAt: record.scheduleUpdatedAt,
    payload: record.schedulePayload,
  }, corsHeaders());
}

async function adminUpdateClass(request, store, code) {
  const record = await store.getClass(code);
  if (!record) return json(404, { error: 'class_not_found', message: '班级不存在' }, corsHeaders());
  const parsed = await readJson(request);
  if (parsed.error) return parsed.error;
  const body = parsed.value ?? {};
  const patch = { updatedAt: Date.now() };
  if (body.name != null) {
    const name = cleanString(body.name, MAX_NAME);
    if (!name) return json(400, { error: 'invalid_name', message: '班级名称需为 1-40 个字符' }, corsHeaders());
    patch.name = name;
  }
  if (body.teacher != null) {
    const teacher = cleanString(body.teacher, MAX_NAME);
    if (teacher === null) return json(400, { error: 'invalid_teacher', message: '教师名称过长' }, corsHeaders());
    patch.teacher = teacher;
  }
  if (body.joinPassword != null) {
    if (body.joinPassword === '') {
      patch.joinPasswordHash = null;
      patch.joinPasswordSalt = null;
    } else {
      const password = cleanString(body.joinPassword, 64);
      if (!password || password.length < 4) {
        return json(400, { error: 'invalid_join_password', message: '加入口令至少 4 个字符' }, corsHeaders());
      }
      patch.joinPasswordSalt = base64Url(randomBytes(12));
      patch.joinPasswordHash = await passwordHash(password, patch.joinPasswordSalt);
    }
  }
  await store.updateClass(code, patch);
  const updated = await store.getClass(code);
  const members = await store.listMembers(code);
  const payload = updated.schedulePayload ?? null;
  return json(200, {
    class: adminClassSummary({
      ...updated,
      memberCount: members.length,
      payloadBytes: payload ? byteLength(payload) : 0,
      hasSchedule: Boolean(payload),
    }),
  }, corsHeaders());
}

async function adminDeleteClass(store, code) {
  const record = await store.getClass(code);
  if (!record) return json(404, { error: 'class_not_found', message: '班级不存在' }, corsHeaders());
  await store.deleteClass(code);
  return json(200, { deleted: true, code }, corsHeaders());
}
