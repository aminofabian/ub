-- Personal contribution (SUPPLIER_ENGAGEMENT_DESIGN.md §5): an append-only record of one person's own work.
-- The unique key makes a replay add nothing. Never a total across people; shown only to the person.
CREATE TABLE supplier_contributions (
    id            VARCHAR(36)  NOT NULL,
    business_id   VARCHAR(36)  NOT NULL,
    user_id       VARCHAR(36)  NOT NULL,
    rule_id       VARCHAR(64)  NOT NULL,
    rule_version  INT          NOT NULL DEFAULT 1,
    source_type   VARCHAR(32)  NOT NULL,
    source_id     VARCHAR(36)  NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_supplier_contributions (business_id, user_id, rule_id, source_type, source_id)
);
