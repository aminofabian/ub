-- Pickup Mtaani shipment mirror (scope §10). One row per web order, created at
-- checkout with book_status=pending; the parcel is created later (book on
-- dispatch or manual). `carrier` leaves room for a second carrier later.

CREATE TABLE web_order_shipments (
  id                      VARCHAR(36)   NOT NULL PRIMARY KEY,
  business_id             VARCHAR(36)   NOT NULL,
  web_order_id            VARCHAR(36)   NOT NULL,
  carrier                 VARCHAR(32)   NOT NULL,
  mode                    VARCHAR(16)   NOT NULL,
  origin_agent_id         BIGINT        NOT NULL,
  destination_agent_id    BIGINT        NULL,
  doorstep_destination_id BIGINT        NULL,
  destination_label       VARCHAR(255)  NULL,
  location_description    VARCHAR(500)  NULL,
  quoted_fee_kes          DECIMAL(14,2) NOT NULL,
  shopper_fee_kes         DECIMAL(14,2) NOT NULL,
  fee_mode                VARCHAR(24)   NOT NULL,
  package_value_kes       INT           NULL,
  upstream_package_id     BIGINT        NULL,
  track_id                VARCHAR(128)  NULL,
  receipt_no              VARCHAR(64)   NULL,
  payment_status          VARCHAR(64)   NULL,
  upstream_state          VARCHAR(64)   NULL,
  last_track_description  VARCHAR(500)  NULL,
  book_status             VARCHAR(32)   NOT NULL,
  book_error              VARCHAR(1000) NULL,
  last_polled_at          TIMESTAMP(6)  NULL,
  booked_at               TIMESTAMP(6)  NULL,
  raw_last_payload        JSON          NULL,
  created_at              TIMESTAMP(6)  NOT NULL,
  updated_at              TIMESTAMP(6)  NOT NULL,
  -- Optimistic lock (scope §13). See WebOrderShipment.version.
  version                 BIGINT        NOT NULL DEFAULT 0,
  CONSTRAINT uq_web_order_shipments_order UNIQUE (web_order_id),
  INDEX idx_web_order_shipments_business (business_id),
  INDEX idx_web_order_shipments_book_status (book_status)
);

-- The shopper's fulfilment choice, persisted between the delivery step and
-- submit (scope §7, §10). JSON: {carrier, mode, destinationId, destinationLabel,
-- locationDescription, quoteId}. Null means "use the normal delivery area".
ALTER TABLE web_checkout_sessions
  ADD COLUMN pickup_mtaani VARCHAR(1000) NULL;

ALTER TABLE shopper_checkout_profiles
  ADD COLUMN pickup_mtaani VARCHAR(1000) NULL;
