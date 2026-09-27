-- One-shot slips sent from Order / Receive to a specific cashier till.

CREATE TABLE till_print_jobs (
  id CHAR(36) NOT NULL PRIMARY KEY,
  business_id CHAR(36) NOT NULL,
  branch_id CHAR(36) NULL,
  target_user_id CHAR(36) NOT NULL,
  kind VARCHAR(16) NOT NULL,
  reference_no VARCHAR(80) NOT NULL,
  payload_json MEDIUMTEXT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  claimed_at DATETIME(6) NULL,
  KEY idx_till_print_pending (business_id, target_user_id, claimed_at, created_at)
);
