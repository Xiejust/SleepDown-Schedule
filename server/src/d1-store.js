/** D1 Store：把 core.js 的 Store 接口映射到 D1 SQL。 */
export class D1Store {
  constructor(db) {
    this.db = db;
  }

  async getClass(code) {
    const row = await this.db.prepare('SELECT * FROM classes WHERE code = ?').bind(code).first();
    return row ? mapClass(row) : null;
  }

  async createClass(record) {
    await this.db.prepare(
      `INSERT INTO classes (
        code, name, teacher, join_password_hash, join_password_salt, admin_token_hash,
        schedule_version, schedule_payload, schedule_updated_at, created_at, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    ).bind(
      record.code, record.name, record.teacher, record.joinPasswordHash, record.joinPasswordSalt,
      record.adminTokenHash, record.scheduleVersion, record.schedulePayload, record.scheduleUpdatedAt,
      record.createdAt, record.updatedAt
    ).run();
  }

  async updateClass(code, patch) {
    const allowed = [
      'name', 'teacher', 'join_password_hash', 'join_password_salt', 'admin_token_hash',
      'schedule_version', 'schedule_payload', 'schedule_updated_at', 'updated_at'
    ];
    const columns = [];
    const values = [];
    for (const [key, value] of Object.entries(patch)) {
      const column = camelToSnake(key);
      if (!allowed.includes(column)) continue;
      columns.push(`${column} = ?`);
      values.push(value);
    }
    if (columns.length === 0) return;
    values.push(code);
    await this.db.prepare(`UPDATE classes SET ${columns.join(', ')} WHERE code = ?`).bind(...values).run();
  }

  async deleteClass(code) {
    await this.db.batch([
      this.db.prepare('DELETE FROM members WHERE class_code = ?').bind(code),
      this.db.prepare('DELETE FROM classes WHERE code = ?').bind(code),
    ]);
  }

  async upsertMember(code, member) {
    await this.db.prepare(
      `INSERT INTO members (class_code, member_id, nickname, role, joined_at, last_seen_at)
       VALUES (?, ?, ?, ?, ?, ?)
       ON CONFLICT(class_code, member_id) DO UPDATE SET
         nickname = excluded.nickname,
         role = excluded.role,
         last_seen_at = excluded.last_seen_at`
    ).bind(code, member.memberId, member.nickname, member.role, member.joinedAt, member.lastSeenAt).run();
  }

  async touchMember(code, memberId, lastSeenAt) {
    await this.db.prepare(
      'UPDATE members SET last_seen_at = ? WHERE class_code = ? AND member_id = ?'
    ).bind(lastSeenAt, code, memberId).run();
  }

  async listMembers(code) {
    const result = await this.db.prepare(
      'SELECT member_id, nickname, role, joined_at, last_seen_at FROM members WHERE class_code = ? ORDER BY joined_at ASC'
    ).bind(code).all();
    return (result.results ?? []).map((row) => ({
      memberId: row.member_id,
      nickname: row.nickname,
      role: row.role,
      joinedAt: row.joined_at,
      lastSeenAt: row.last_seen_at,
    }));
  }
}

function camelToSnake(value) {
  return value.replace(/[A-Z]/g, (c) => `_${c.toLowerCase()}`);
}

function mapClass(row) {
  return {
    code: row.code,
    name: row.name,
    teacher: row.teacher ?? '',
    joinPasswordHash: row.join_password_hash,
    joinPasswordSalt: row.join_password_salt,
    adminTokenHash: row.admin_token_hash,
    scheduleVersion: row.schedule_version ?? 0,
    schedulePayload: row.schedule_payload,
    scheduleUpdatedAt: row.schedule_updated_at,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}