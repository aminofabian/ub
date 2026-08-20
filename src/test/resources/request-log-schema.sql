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
  success        TINYINT      NOT NULL,
  duration_ms    BIGINT       NOT NULL,
  ip             VARCHAR(64)  NULL
);
