-- Per-sale wallet credit notification dispatch audit (WhatsApp / SMS / in-app).

CREATE TABLE wallet_credit_notification_dispatches (
  id              CHAR(36)     PRIMARY KEY,
  business_id     CHAR(36)     NOT NULL,
  sale_id         CHAR(36)     NOT NULL,
  customer_id     CHAR(36)     NOT NULL,
  channel         VARCHAR(24)  NOT NULL,
  outcome         VARCHAR(24)  NOT NULL,
  detail          VARCHAR(500) NULL,
  message_preview VARCHAR(500) NULL,
  sent_at         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_wallet_credit_notif_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  UNIQUE KEY uq_wallet_credit_notif_sale (sale_id)
);

CREATE INDEX idx_wallet_credit_notif_business_sent ON wallet_credit_notification_dispatches (business_id, sent_at);
