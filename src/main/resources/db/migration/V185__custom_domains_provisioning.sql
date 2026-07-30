-- Custom domains P0: provisioning columns on domains + domain_orders for P1 buy flow.
-- Soft-deleted hostnames are vacated (suffix -{id}) so names can be reclaimed under UNIQUE(domain).

ALTER TABLE domains
  ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' AFTER active,
  ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'MANUAL_CONNECT' AFTER status,
  ADD COLUMN zone_source VARCHAR(32) NULL AFTER source,
  ADD COLUMN hostafrica_domain_id VARCHAR(64) NULL AFTER zone_source,
  ADD COLUMN verified_at TIMESTAMP NULL AFTER hostafrica_domain_id,
  ADD COLUMN last_error TEXT NULL AFTER verified_at,
  ADD COLUMN dns_instruction_json JSON NULL AFTER last_error;

-- Existing platform subdomains (created at onboard) stay live; mark source.
UPDATE domains
   SET source = 'PLATFORM_SUBDOMAIN',
       status = 'ACTIVE',
       zone_source = NULL
 WHERE deleted_at IS NULL
   AND (
     domain LIKE '%.kiosk.ke'
     OR domain LIKE '%.palmart.co.ke'
     OR domain LIKE '%.localhost'
     OR domain LIKE '%.test.local'
   );

CREATE TABLE domain_orders (
  id                    CHAR(36) PRIMARY KEY,
  business_id           CHAR(36) NOT NULL,
  fqdn                  VARCHAR(255) NOT NULL,
  status                VARCHAR(32) NOT NULL DEFAULT 'QUOTED',
  hostafrica_domain_id  VARCHAR(64) NULL,
  register_url          TEXT NULL,
  price_cents           BIGINT NULL,
  currency              VARCHAR(8) NULL,
  vercel_zone_ready     BOOLEAN NOT NULL DEFAULT FALSE,
  ns_status             VARCHAR(32) NOT NULL DEFAULT 'PENDING_OPS',
  domain_mapping_id     CHAR(36) NULL,
  last_error            TEXT NULL,
  created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at            TIMESTAMP NULL,
  CONSTRAINT fk_domain_orders_business FOREIGN KEY (business_id) REFERENCES businesses(id),
  CONSTRAINT fk_domain_orders_mapping FOREIGN KEY (domain_mapping_id) REFERENCES domains(id)
);

CREATE INDEX idx_domain_orders_business ON domain_orders (business_id);
CREATE INDEX idx_domain_orders_fqdn ON domain_orders (fqdn);
CREATE INDEX idx_domain_orders_status ON domain_orders (status);
