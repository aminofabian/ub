-- SupplyBatchExpense — extra costs (transport, handling, customs, storage) linked to a supply batch.
-- Note: closed_at / closed_by were added to supply_batches in V62.

CREATE TABLE supply_batch_expenses (
    id              CHAR(36)      PRIMARY KEY,
    business_id     CHAR(36)      NOT NULL,
    supply_batch_id CHAR(36)      NOT NULL,
    category        VARCHAR(32)   NOT NULL,
    amount          DECIMAL(14,2) NOT NULL,
    description     VARCHAR(500)  NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      CHAR(36)      NULL,
    CONSTRAINT fk_sbe_business     FOREIGN KEY (business_id)     REFERENCES businesses (id),
    CONSTRAINT fk_sbe_supply_batch FOREIGN KEY (supply_batch_id) REFERENCES supply_batches (id)
);

CREATE INDEX idx_sbe_supply_batch ON supply_batch_expenses (supply_batch_id);
CREATE INDEX idx_sbe_business_cat ON supply_batch_expenses (business_id, category);
