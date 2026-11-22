-- 班级课表同步 D1 表结构
CREATE TABLE IF NOT EXISTS classes (
  code TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  teacher TEXT NOT NULL DEFAULT '',
  join_password_hash TEXT,
  join_password_salt TEXT,
  admin_token_hash TEXT NOT NULL,
  schedule_version INTEGER NOT NULL DEFAULT 0,
  schedule_payload TEXT,
  schedule_updated_at INTEGER,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS members (
  class_code TEXT NOT NULL,
  member_id TEXT NOT NULL,
  nickname TEXT NOT NULL DEFAULT '',
  role TEXT NOT NULL DEFAULT 'member',
  joined_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  PRIMARY KEY (class_code, member_id)
);

CREATE INDEX IF NOT EXISTS idx_members_class ON members(class_code, joined_at);