import test from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { handleRequest } from '../src/core.js';
import { MemoryStore } from '../src/memory-store.js';

if (!globalThis.crypto) globalThis.__sleepdownWebcrypto = webcrypto;

const payload = JSON.stringify({
  courses: [
    {
      name: '高等数学',
      teacher: '张老师',
      position: '教学楼 A101',
      day: 1,
      weeks: [1, 2, 3],
      startSection: 1,
      endSection: 2,
    },
  ],
  timeSlots: [
    { number: 1, startTime: '08:00', endTime: '08:45' },
    { number: 2, startTime: '08:55', endTime: '09:40' },
  ],
});

function request(store, method, path, { body, token } = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  return handleRequest(new Request(`https://class.example.com${path}`, {
    method,
    headers,
    body: body == null ? undefined : JSON.stringify(body),
  }), store);
}

async function createClass(store, joinPassword) {
  const response = await request(store, 'POST', '/api/v1/classes', {
    body: { name: '临床医学 2024-1 班', teacher: '李老师', joinPassword, nickname: '李老师' },
  });
  assert.equal(response.status, 201);
  return response.json();
}

test('创建班级、加入校验与发布/拉取课表', async () => {
  const store = new MemoryStore();
  const created = await createClass(store, '1234');
  assert.equal(created.code.length, 8);
  assert.match(created.displayCode, /^[A-Z0-9]{4}-[A-Z0-9]{4}$/);
  assert.ok(created.adminToken.length > 20);
  assert.equal(created.class.name, '临床医学 2024-1 班');
  assert.equal(created.class.joinPasswordRequired, true);

  const wrong = await request(store, 'POST', `/api/v1/classes/${created.code}/join`, {
    body: { nickname: '小明', password: '0000' },
  });
  assert.equal(wrong.status, 403);

  const joined = await request(store, 'POST', `/api/v1/classes/${created.code}/join`, {
    body: { nickname: '小明', password: '1234' },
  });
  assert.equal(joined.status, 200);
  const joinedBody = await joined.json();
  assert.equal(joinedBody.class.memberCount, 2);
  assert.equal(joinedBody.schedule, null);
  assert.ok(joinedBody.memberId);

  const denied = await request(store, 'PUT', `/api/v1/classes/${created.code}/schedule`, {
    body: { payload },
  });
  assert.equal(denied.status, 401);

  const published = await request(store, 'PUT', `/api/v1/classes/${created.code}/schedule`, {
    token: created.adminToken,
    body: { payload },
  });
  assert.equal(published.status, 200);
  const publishedBody = await published.json();
  assert.equal(publishedBody.version, 1);

  const pulled = await request(store, 'GET', `/api/v1/classes/${created.code}/schedule`);
  assert.equal(pulled.status, 200);
  const pulledBody = await pulled.json();
  assert.equal(pulledBody.schedule.version, 1);
  assert.equal(pulledBody.schedule.payload, payload);

  const notModified = await request(store, 'GET', `/api/v1/classes/${created.code}/schedule?version=1`);
  assert.equal(notModified.status, 200);
  assert.equal((await notModified.json()).notModified, true);

  const second = await request(store, 'PUT', `/api/v1/classes/${created.code}/schedule`, {
    token: created.adminToken,
    body: { payload, expectedVersion: 1 },
  });
  assert.equal((await second.json()).version, 2);

  const conflict = await request(store, 'PUT', `/api/v1/classes/${created.code}/schedule`, {
    token: created.adminToken,
    body: { payload, expectedVersion: 1 },
  });
  assert.equal(conflict.status, 409);
});

test('非法课表内容会被拒绝', async () => {
  const store = new MemoryStore();
  const created = await createClass(store);
  const invalidJson = await request(store, 'PUT', `/api/v1/classes/${created.code}/schedule`, {
    token: created.adminToken,
    body: { payload: 'not-json' },
  });
  assert.equal(invalidJson.status, 400);

  const missingCourses = await request(store, 'PUT', `/api/v1/classes/${created.code}/schedule`, {
    token: created.adminToken,
    body: { payload: JSON.stringify({ timeSlots: [] }) },
  });
  assert.equal(missingCourses.status, 400);

  const tooLarge = await request(store, 'PUT', `/api/v1/classes/${created.code}/schedule`, {
    token: created.adminToken,
    body: { payload: JSON.stringify({ courses: [{ name: 'x'.repeat(600 * 1024) }] }) },
  });
  assert.equal(tooLarge.status, 413);
});

test('管理员可以查看成员、修改信息并删除班级', async () => {
  const store = new MemoryStore();
  const created = await createClass(store);
  await request(store, 'POST', `/api/v1/classes/${created.code}/join`, {
    body: { nickname: '小红' },
  });

  const overview = await request(store, 'GET', `/api/v1/classes/${created.code}/admin`, {
    token: created.adminToken,
  });
  assert.equal(overview.status, 200);
  const overviewBody = await overview.json();
  assert.equal(overviewBody.members.length, 2);
  assert.deepEqual(overviewBody.members.map((m) => m.nickname), ['李老师', '小红']);

  const patched = await request(store, 'PATCH', `/api/v1/classes/${created.code}`, {
    token: created.adminToken,
    body: { name: '新学期班级', joinPassword: 'abcd' },
  });
  assert.equal(patched.status, 200);
  assert.equal((await patched.json()).class.name, '新学期班级');

  const deleted = await request(store, 'DELETE', `/api/v1/classes/${created.code}`, {
    token: created.adminToken,
  });
  assert.equal(deleted.status, 200);
  assert.equal(await store.getClass(created.code), null);
});

test('拉取课表会更新成员最近同步时间', async () => {
  const store = new MemoryStore();
  const created = await createClass(store);
  const joined = await request(store, 'POST', `/api/v1/classes/${created.code}/join`, {
    body: { nickname: '小明', memberId: 'member-1' },
  });
  const memberId = (await joined.json()).memberId;
  await store.upsertMember(created.code, {
    memberId,
    nickname: '小明',
    role: 'member',
    joinedAt: 1,
    lastSeenAt: 1,
  });

  const pulled = await request(store, 'GET', `/api/v1/classes/${created.code}/schedule?memberId=${memberId}`);
  assert.equal(pulled.status, 200);
  const member = (await store.listMembers(created.code)).find((item) => item.memberId === memberId);
  assert.ok(member.lastSeenAt > 1);
});

test('同一成员重复加入不会重复计数', async () => {
  const store = new MemoryStore();
  const created = await createClass(store);
  const first = await request(store, 'POST', `/api/v1/classes/${created.code}/join`, {
    body: { nickname: '小明', memberId: 'member-1' },
  });
  const memberId = (await first.json()).memberId;
  const second = await request(store, 'POST', `/api/v1/classes/${created.code}/join`, {
    body: { nickname: '小明', memberId },
  });
  assert.equal((await second.json()).class.memberCount, 2);
});