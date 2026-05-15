CREATE TABLE stock_take_line_batches (
    id              VARCHAR(36)  NOT NULL PRIMARY KEY,
    line_id         VARCHAR(36)  NOT NULL,
    batch_id        VARCHAR(36)  NOT NULL,
    batch_number    VARCHAR(64)  NULL,
    expiry_date     DATE         NULL,
    system_qty_snapshot DECIMAL(14, 4) NOT NULL,
    counted_qty     DECIMAL(14, 4) NULL,
    sort_order      INT          NOT NULL DEFAULT 0,

    CONSTRAINT fk_stlb_line FOREIGN KEY (line_id) REFERENCES stock_take_lines (id) ON DELETE CASCADE
);

CREATE INDEX idx_stlb_line_id ON stock_take_line_batches (line_id);
CREATE INDEX idx_stlb_batch_id ON stock_take_line_batches (batch_id);
