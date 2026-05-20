-- Per-sale credit tab reminder dispatch audit (WhatsApp / SMS / in-app).

CREATE TABLE credit_sale_reminder_dispatches (
  id              CHAR(36)     PRIMARY KEY,
  business_id     CHAR(36)     NOT NULL,
  sale_id         CHAR(36)     NOT NULL,
  customer_id     CHAR(36)     NOT NULL,
  channel         VARCHAR(24)  NOT NULL,
  outcome         VARCHAR(24)  NOT NULL,
  detail          VARCHAR(500) NULL,
  message_preview VARCHAR(500) NULL,
  sent_at         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_credit_sale_reminder_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  UNIQUE KEY uq_credit_sale_reminder_sale (sale_id)
);

CREATE INDEX idx_credit_sale_reminder_business_sent ON credit_sale_reminder_dispatches (business_id, sent_at);
