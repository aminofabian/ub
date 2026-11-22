-- H2-compatible mirror of V225__platform_request_log.sql for @DataJpaTest.
-- Type-faithful (TINYINT/BIGINT/TIMESTAMP(6)/CHAR(36)) but without MySQL-only
-- display-width syntax that H2's parser rejects.

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
  success        BOOLEAN      NOT NULL,
  duration_ms    BIGINT       NOT NULL,
  ip             VARCHAR(64)  NULL,
  load_test_run_id CHAR(36)   NULL,
  -- Added by V246 and later; kept in step with PlatformRequestLog.
  error_title    VARCHAR(255) NULL,
  error_detail   CLOB         NULL,
  error_type     VARCHAR(255) NULL,
  exception_class VARCHAR(255) NULL,
  exception_chain CLOB        NULL,
  stack_summary  CLOB         NULL,
  user_agent     VARCHAR(512) NULL,
  request_meta   CLOB         NULL
);
