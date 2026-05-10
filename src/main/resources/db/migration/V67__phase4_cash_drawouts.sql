-- Phase 4 Slice 3 — Cash Drawouts Module

CREATE TABLE IF NOT EXISTS cash_drawouts (
  id                CHAR(36)      PRIMARY KEY,
  shift_id          CHAR(36)      NOT NULL,
  register_id       CHAR(36)      NULL,
  category          VARCHAR(20)   NOT NULL,
  recurring_item_id CHAR(36)      NULL,
  amount            DECIMAL(12,2) NOT NULL,
  description       VARCHAR(300)  NOT NULL,
  recipient_name    VARCHAR(255)  NOT NULL,
  recipient_contact VARCHAR(100)  NULL,
  reference         VARCHAR(255)  NULL,
  status            VARCHAR(20)   NOT NULL DEFAULT 'PENDING_APPROVAL',
  approval_tier     INT           NOT NULL DEFAULT 1,
  initiated_by      CHAR(36)      NOT NULL,
  approved_by       CHAR(36)      NULL,
  approved_at       TIMESTAMP     NULL,
  rejected_by       CHAR(36)      NULL,
  rejection_reason  VARCHAR(500)  NULL,
  voided_by         CHAR(36)      NULL,
  void_reason       VARCHAR(500)  NULL,
  voided_at         TIMESTAMP     NULL,
  expires_at        TIMESTAMP     NULL,
  created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_cash_drawouts_shift FOREIGN KEY (shift_id) REFERENCES shifts (id),
  CONSTRAINT fk_cash_drawouts_initiated_by FOREIGN KEY (initiated_by) REFERENCES users (id),
  CONSTRAINT fk_cash_drawouts_approved_by FOREIGN KEY (approved_by) REFERENCES users (id),
  CONSTRAINT fk_cash_drawouts_rejected_by FOREIGN KEY (rejected_by) REFERENCES users (id),
  CONSTRAINT fk_cash_drawouts_voided_by FOREIGN KEY (voided_by) REFERENCES users (id),
  INDEX idx_cash_drawouts_shift (shift_id, status),
  INDEX idx_cash_drawouts_status (status, expires_at),
  INDEX idx_cash_drawouts_initiated_by (initiated_by)
);

CREATE TABLE IF NOT EXISTS recurring_drawout_items (
  id                  CHAR(36)      PRIMARY KEY,
  business_id         CHAR(36)      NOT NULL,
  name                VARCHAR(255)  NOT NULL,
  category            VARCHAR(20)   NOT NULL,
  default_amount      DECIMAL(12,2) NOT NULL,
  amount_tolerance    DECIMAL(5,2)  NOT NULL DEFAULT 20.00,
  default_description VARCHAR(300)  NULL,
  default_recipient   VARCHAR(255)  NULL,
  frequency           VARCHAR(16)   NOT NULL DEFAULT 'DAILY',
  max_per_shift       INT           NULL,
  requires_approval   BOOLEAN       NOT NULL DEFAULT FALSE,
  is_active           BOOLEAN       NOT NULL DEFAULT TRUE,
  created_by          CHAR(36)      NOT NULL,
  created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_recurring_drawout_items_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_recurring_drawout_items_created_by FOREIGN KEY (created_by) REFERENCES users (id),
  INDEX idx_recurring_items_business_active (business_id, is_active)
);

CREATE TABLE IF NOT EXISTS drawout_approval_thresholds (
  id              CHAR(36)      PRIMARY KEY,
  business_id     CHAR(36)      NOT NULL,
  tier            INT           NOT NULL,
  min_amount      DECIMAL(12,2) NOT NULL,
  max_amount      DECIMAL(12,2) NULL,
  approval_role   VARCHAR(16)   NOT NULL,
  approval_method VARCHAR(16)   NOT NULL,
  updated_by      CHAR(36)      NULL,
  updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_drawout_approval_thresholds_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_drawout_approval_thresholds_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
  CONSTRAINT uq_drawout_threshold_tier UNIQUE (business_id, tier)
);

INSERT INTO drawout_approval_thresholds (id, business_id, tier, min_amount, max_amount, approval_role, approval_method, updated_by, updated_at)
SELECT UUID(), b.id, 1, 1.00, 500.00, 'NONE', 'NONE', NULL, CURRENT_TIMESTAMP
FROM businesses b WHERE b.deleted_at IS NULL
AND NOT EXISTS (SELECT 1 FROM drawout_approval_thresholds t WHERE t.business_id = b.id AND t.tier = 1);

INSERT INTO drawout_approval_thresholds (id, business_id, tier, min_amount, max_amount, approval_role, approval_method, updated_by, updated_at)
SELECT UUID(), b.id, 2, 501.00, 2000.00, 'SUPERVISOR', 'PIN', NULL, CURRENT_TIMESTAMP
FROM businesses b WHERE b.deleted_at IS NULL
AND NOT EXISTS (SELECT 1 FROM drawout_approval_thresholds t WHERE t.business_id = b.id AND t.tier = 2);

INSERT INTO drawout_approval_thresholds (id, business_id, tier, min_amount, max_amount, approval_role, approval_method, updated_by, updated_at)
SELECT UUID(), b.id, 3, 2001.00, 10000.00, 'SUPERVISOR', 'PRESENCE', NULL, CURRENT_TIMESTAMP
FROM businesses b WHERE b.deleted_at IS NULL
AND NOT EXISTS (SELECT 1 FROM drawout_approval_thresholds t WHERE t.business_id = b.id AND t.tier = 3);

INSERT INTO drawout_approval_thresholds (id, business_id, tier, min_amount, max_amount, approval_role, approval_method, updated_by, updated_at)
SELECT UUID(), b.id, 4, 10001.00, NULL, 'MANAGER', 'PRESENCE', NULL, CURRENT_TIMESTAMP
FROM businesses b WHERE b.deleted_at IS NULL
AND NOT EXISTS (SELECT 1 FROM drawout_approval_thresholds t WHERE t.business_id = b.id AND t.tier = 4);
