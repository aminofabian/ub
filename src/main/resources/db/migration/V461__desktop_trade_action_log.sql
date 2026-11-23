-- Desktop supplier trade sync — cloud-side idempotency log (DESKTOP_SUPPLIER_TRADE_SYNC_SCOPE.md §S2).
--
-- When a till pushes an action (answered / dispatched / cancelled an order), the
-- cloud applies it through the same service the web calls. This log remembers
-- each action id and its outcome, so a retried push returns the first outcome
-- instead of applying twice. Lives on the cloud; harmless (empty) on the till.
CREATE TABLE IF NOT EXISTS desktop_trade_action_log (
  action_id       VARCHAR(36)   NOT NULL,
  business_id     VARCHAR(36)   NOT NULL,
  kind            VARCHAR(32)   NOT NULL,
  target_id       VARCHAR(36)   NOT NULL,
  -- applied | refused
  outcome         VARCHAR(16)   NOT NULL,
  outcome_detail  VARCHAR(500)  NULL,
  applied_at      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (action_id),
  KEY idx_dtal_business (business_id)
);
