CREATE TABLE note_ai_turns (
  id TEXT PRIMARY KEY, note_id TEXT NOT NULL REFERENCES notes(id),
  job_id TEXT NOT NULL UNIQUE REFERENCES jobs(id),
  user_message TEXT NOT NULL, assistant_message TEXT,
  result_revision INTEGER, changed INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL
);
CREATE INDEX note_ai_history ON note_ai_turns(note_id,created_at);
UPDATE app_metadata SET value='3' WHERE key='storage_format';
