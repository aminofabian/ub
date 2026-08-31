-- CSV import jobs: non-blocking per-import notices (e.g. supplier not found,
-- columns ignored) surfaced separately from hard validation errors.

ALTER TABLE import_jobs ADD COLUMN warnings_json JSON NULL;
