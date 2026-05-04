-- Phase 7 Slice 1 — MV refresh observability table (PHASE_7_PLAN.md §Slice 1).
-- MySQL adaptation: no CREATE MATERIALIZED VIEW; mv_* will be plain summary tables
-- populated by application services. This table audits every refresh attempt so the
-- "lag metric" + "block export if lag > N hours" risk mitigations have a real source.

CREATE TABLE reporting_refresh_runs (
  id              CHAR(36)      PRIMARY KEY,
  mv_name         VARCHAR(100)  NOT NULL,
  business_id     CHAR(36)      NULL,
  status          VARCHAR(16)   NOT NULL,            -- running | success | failed
  rows_changed    BIGINT        NULL,
  started_at      TIMESTAMP(3)  NOT NULL,
  finished_at     TIMESTAMP(3)  NULL,
  duration_ms     BIGINT        NULL,
  error_message   VARCHAR(2000) NULL,
  created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_rrr_mv_started ON reporting_refresh_runs (mv_name, started_at);
CREATE INDEX idx_rrr_status ON reporting_refresh_runs (status, started_at);
