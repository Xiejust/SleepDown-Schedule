/** 内存 Store：本地开发与测试使用。 */
export class MemoryStore {
  constructor() {
    this.classes = new Map();
    this.members = new Map();
  }

  async getClass(code) {
    return this.classes.get(code) ?? null;
  }

  async createClass(record) {
    if (this.classes.has(record.code)) throw new Error('code_conflict');
    this.classes.set(record.code, { ...record });
  }

  async updateClass(code, patch) {
    const current = this.classes.get(code);
    if (!current) return;
    this.classes.set(code, { ...current, ...patch });
  }

  async deleteClass(code) {
    this.classes.delete(code);
    this.members.delete(code);
  }

  async upsertMember(code, member) {
    if (!this.members.has(code)) this.members.set(code, new Map());
    const existing = this.members.get(code).get(member.memberId);
    this.members.get(code).set(member.memberId, {
      ...member,
      joinedAt: existing?.joinedAt ?? member.joinedAt,
    });
  }

  async touchMember(code, memberId, lastSeenAt) {
    const member = this.members.get(code)?.get(memberId);
    if (member) member.lastSeenAt = lastSeenAt;
  }

  async listMembers(code) {
    return [...(this.members.get(code)?.values() ?? [])].sort((a, b) => a.joinedAt - b.joinedAt);
  }
}