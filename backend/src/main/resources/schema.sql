CREATE TABLE IF NOT EXISTS recordings (
  id TEXT PRIMARY KEY, title TEXT NOT NULL, course TEXT NOT NULL, recorded_on TEXT NOT NULL,
  category TEXT NOT NULL, filename TEXT NOT NULL, stored_name TEXT NOT NULL, size INTEGER NOT NULL,
  duration_ms INTEGER, transcript_id TEXT, transcript_status TEXT NOT NULL DEFAULT 'UPLOADED',
  summary_status TEXT NOT NULL DEFAULT 'PENDING', sync_status TEXT NOT NULL DEFAULT 'PENDING',
  last_error TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS transcripts (
  id TEXT PRIMARY KEY, recording_id TEXT NOT NULL REFERENCES recordings(id), job_id TEXT NOT NULL,
  provider TEXT NOT NULL, duration_ms INTEGER NOT NULL, text TEXT NOT NULL, segments TEXT NOT NULL,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS notes (
  id TEXT PRIMARY KEY, recording_id TEXT UNIQUE NOT NULL REFERENCES recordings(id), title TEXT NOT NULL,
  markdown TEXT NOT NULL, category TEXT NOT NULL, revision INTEGER NOT NULL, confirmed_revision INTEGER,
  synced_revision INTEGER, notion_page_id TEXT, notion_url TEXT, notion_target TEXT, notion_pending_target TEXT,
  created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS note_versions (
  id INTEGER PRIMARY KEY AUTOINCREMENT, note_id TEXT NOT NULL REFERENCES notes(id), revision INTEGER NOT NULL,
  title TEXT NOT NULL, markdown TEXT NOT NULL, category TEXT NOT NULL, source TEXT NOT NULL,
  created_at TEXT NOT NULL, UNIQUE(note_id, revision)
);
CREATE TABLE IF NOT EXISTS jobs (
  id TEXT PRIMARY KEY, recording_id TEXT NOT NULL REFERENCES recordings(id), stage TEXT NOT NULL,
  status TEXT NOT NULL, attempt INTEGER NOT NULL, note_revision INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL, started_at TEXT, finished_at TEXT, error TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS one_active_job ON jobs(recording_id) WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX IF NOT EXISTS jobs_queue ON jobs(status, created_at);
CREATE TABLE IF NOT EXISTS logs (
  id INTEGER PRIMARY KEY AUTOINCREMENT, recording_id TEXT NOT NULL REFERENCES recordings(id),
  job_id TEXT, level TEXT NOT NULL, message TEXT NOT NULL, created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS artifacts (
  id INTEGER PRIMARY KEY AUTOINCREMENT, recording_id TEXT NOT NULL REFERENCES recordings(id),
  job_id TEXT NOT NULL, kind TEXT NOT NULL, content TEXT NOT NULL, created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS recording_archive ON recordings(recorded_on DESC, course);
