-- Email retry tracking for merchant onboarding sequence.
-- A FAILED email row is retried by the scheduler while retry_count < 2 and
-- next_retry_at is due; after two retries the step is terminal.
ALTER TABLE merchant_onboarding_send
  ADD COLUMN retry_count INT NOT NULL DEFAULT 0,
  ADD COLUMN next_retry_at DATETIME(6) NULL;
