CREATE TABLE businesses (
  id                     CHAR(36) PRIMARY KEY,
  name                   VARCHAR(255) NOT NULL,
  slug                   VARCHAR(191) NOT NULL UNIQUE,
  currency               CHAR(3) NOT NULL DEFAULT 'KES',
  timezone               VARCHAR(100) NOT NULL DEFAULT 'Africa/Nairobi',
  country_code           CHAR(2) NOT NULL DEFAULT 'KE',
  active                 BOOLEAN NOT NULL DEFAULT TRUE,
  subscription_tier      VARCHAR(64) NOT NULL DEFAULT 'starter',
  subscription_renews_at TIMESTAMP NULL,
  settings               JSON NOT NULL,
  created_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by             CHAR(36) NULL,
  updated_by             CHAR(36) NULL,
  deleted_at             TIMESTAMP NULL
);

CREATE TABLE branches (
  id          CHAR(36) PRIMARY KEY,
  business_id CHAR(36) NOT NULL,
  name        VARCHAR(255) NOT NULL,
  address     VARCHAR(500) NULL,
  active      BOOLEAN NOT NULL DEFAULT TRUE,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by  CHAR(36) NULL,
  updated_by  CHAR(36) NULL,
  deleted_at  TIMESTAMP NULL,
  UNIQUE KEY uq_branches_business_name (business_id, name),
  CONSTRAINT fk_branches_business FOREIGN KEY (business_id) REFERENCES businesses(id)
);

CREATE TABLE domains (
  id          CHAR(36) PRIMARY KEY,
  business_id CHAR(36) NOT NULL,
  domain      VARCHAR(255) NOT NULL UNIQUE,
  is_primary  BOOLEAN NOT NULL DEFAULT FALSE,
  primary_business_id CHAR(36) GENERATED ALWAYS AS (
    CASE WHEN is_primary THEN business_id ELSE NULL END
  ) STORED,
  active      BOOLEAN NOT NULL DEFAULT TRUE,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by  CHAR(36) NULL,
  updated_by  CHAR(36) NULL,
  deleted_at  TIMESTAMP NULL,
  CONSTRAINT fk_domains_business FOREIGN KEY (business_id) REFERENCES businesses(id)
);

CREATE UNIQUE INDEX uq_domains_primary
  ON domains (primary_business_id);
