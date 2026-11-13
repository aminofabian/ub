-- Service catalogue for service businesses (kinyozi, salon, spa) — ADR 0014, ticket T2.
--
-- A service is its own entity, not an item: no stock, has a duration, and is later sold as a
-- SERVICE sale line attributed to a staff profile. Additive only: three new tables and one
-- permission. No existing table is altered.
--
-- name_key holds the lower-cased trimmed name while the row is live and NULL once a service
-- is soft-deleted, so UNIQUE (business_id, name_key) enforces "unique among live rows"
-- (MySQL and MariaDB both allow many NULLs in a unique key).

CREATE TABLE IF NOT EXISTS service_categories (
  id           CHAR(36)     NOT NULL PRIMARY KEY,
  business_id  CHAR(36)     NOT NULL,
  name         VARCHAR(191) NOT NULL,
  name_key     VARCHAR(191) NOT NULL,
  sort_order   INT          NOT NULL DEFAULT 0,
  active       BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_service_categories_business_name (business_id, name_key),
  CONSTRAINT fk_service_categories_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

CREATE TABLE IF NOT EXISTS services (
  id                 CHAR(36)      NOT NULL PRIMARY KEY,
  business_id        CHAR(36)      NOT NULL,
  category_id        CHAR(36)      NULL,
  name               VARCHAR(191)  NOT NULL,
  name_key           VARCHAR(191)  NULL,
  description        VARCHAR(1000) NULL,
  duration_minutes   INT           NOT NULL,
  price              DECIMAL(14,2) NOT NULL,
  price_is_variable  BOOLEAN       NOT NULL DEFAULT FALSE,
  commission_type    VARCHAR(16)   NULL,
  commission_value   DECIMAL(14,2) NULL,
  sort_order         INT           NOT NULL DEFAULT 0,
  active             BOOLEAN       NOT NULL DEFAULT TRUE,
  version            BIGINT        NOT NULL DEFAULT 0,
  created_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at         TIMESTAMP     NULL,
  UNIQUE KEY uq_services_business_name (business_id, name_key),
  KEY idx_services_business_active (business_id, active, deleted_at),
  KEY idx_services_business_category (business_id, category_id),
  CONSTRAINT fk_services_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_services_category FOREIGN KEY (category_id) REFERENCES service_categories (id)
);

-- Who may perform a service. No rows for a service = any bookable staff member.
CREATE TABLE IF NOT EXISTS service_staff (
  service_id        CHAR(36)  NOT NULL,
  staff_profile_id  CHAR(36)  NOT NULL,
  business_id       CHAR(36)  NOT NULL,
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (service_id, staff_profile_id),
  KEY idx_service_staff_business_staff (business_id, staff_profile_id),
  CONSTRAINT fk_service_staff_service FOREIGN KEY (service_id) REFERENCES services (id),
  CONSTRAINT fk_service_staff_profile FOREIGN KEY (staff_profile_id) REFERENCES staff_profiles (id),
  CONSTRAINT fk_service_staff_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

-- INSERT IGNORE so a re-run after a partial failure does not die on rows already written.
INSERT IGNORE INTO permissions (id, permission_key, description) VALUES
  ('5d0c7a1e-3b52-4f1a-9c64-7e2a1f0b6d31', 'services.catalog.manage',
   'Create, edit and remove services, service categories and who may perform each service.');

-- Owner: 22222222-0000-0000-0000-000000000001
-- Admin: 22222222-0000-0000-0000-000000000002
INSERT IGNORE INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '5d0c7a1e-3b52-4f1a-9c64-7e2a1f0b6d31'),
  ('22222222-0000-0000-0000-000000000002', '5d0c7a1e-3b52-4f1a-9c64-7e2a1f0b6d31');
