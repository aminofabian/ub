-- Supplier engagement milestones (SUPPLIER_ENGAGEMENT_DESIGN.md §5). Written once per business and key:
-- the unique key makes a replay write nothing a second time.
CREATE TABLE supplier_milestones (
  id             VARCHAR(36)  NOT NULL,
  business_id    VARCHAR(36)  NOT NULL,
  milestone_key  VARCHAR(64)  NOT NULL,
  achieved_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_supplier_milestones (business_id, milestone_key)
);
