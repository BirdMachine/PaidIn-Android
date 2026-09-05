CREATE TABLE IF NOT EXISTS jobs (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  company TEXT NOT NULL,
  location TEXT NOT NULL,
  remote_status TEXT NOT NULL,
  salary_min INTEGER,
  salary_max INTEGER,
  source TEXT NOT NULL,
  source_url TEXT NOT NULL UNIQUE,
  description TEXT NOT NULL,
  skills_json TEXT NOT NULL DEFAULT '[]',
  score INTEGER NOT NULL,
  fit_summary TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'NEW',
  discovered_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_jobs_score ON jobs(score DESC);
CREATE INDEX IF NOT EXISTS idx_jobs_status ON jobs(status);
CREATE INDEX IF NOT EXISTS idx_jobs_discovered_at ON jobs(discovered_at DESC);

CREATE TABLE IF NOT EXISTS settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS scan_runs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  started_at TEXT NOT NULL,
  completed_at TEXT,
  status TEXT NOT NULL,
  jobs_found INTEGER NOT NULL DEFAULT 0,
  error TEXT
);
