-- Supplier engagement follow-ups (SUPPLIER_ENGAGEMENT_DESIGN.md §12.8).
-- A milestone note is shown once: seen_at is set when the supplier dismisses it.
ALTER TABLE supplier_milestones
    ADD seen_at TIMESTAMP NULL;

-- Decision D2: a supplier with no shop has no business to carry a flag, so the readiness switch sits on its page.
ALTER TABLE marketplace_suppliers
    ADD engagement_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Notification budget: at most one a day per supplier. One row per business, the last day a note was sent.
CREATE TABLE supplier_notification_log (
    business_id   VARCHAR(36) NOT NULL,
    last_sent_on  DATE        NOT NULL,
    PRIMARY KEY (business_id)
);
