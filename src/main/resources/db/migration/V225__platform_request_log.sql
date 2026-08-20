-- Platform request log (Super Admin → Platform → Logs).
--
-- One row per API/webhook request handled by the platform backend, captured by
-- PlatformRequestLogInterceptor so super-admins can watch live traffic —
-- cashier sales, M-Pesa, airtime purchases, KPLC tokens — with per-category
-- success counts.

CREATE TABLE platform_request_log (
  id             CHAR(36)     NOT NULL PRIMARY KEY,
  logged_at      TIMESTAMP(6) NOT NULL,
  method         VARCHAR(10)  NOT NULL,
  path           VARCHAR(512) NOT NULL,
  category       VARCHAR(32)  NOT NULL,
  business_id    CHAR(36)     NULL,
  user_id        CHAR(36)     NULL,
  branch_id      CHAR(36)     NULL,
  correlation_id VARCHAR(64)  NULL,
  status         INT          NOT NULL,
  success        TINYINT(1)   NOT NULL,
  duration_ms    BIGINT       NOT NULL,
  ip             VARCHAR(64)  NULL,
  INDEX idx_plog_logged (logged_at),
  INDEX idx_plog_cat_logged (category, logged_at)
);
