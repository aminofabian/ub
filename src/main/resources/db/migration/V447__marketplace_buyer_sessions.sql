-- ADR 0043: a buyer signed in on the marketplace with a phone code. Only a hash of the token is
-- kept. The shops the phone may buy for are worked out on every request, never stored here.

CREATE TABLE marketplace_buyer_sessions (
  id          VARCHAR(36)  NOT NULL,
  token_hash  VARCHAR(64)  NOT NULL,
  phone       VARCHAR(32)  NOT NULL,
  expires_at  TIMESTAMP    NOT NULL,
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_marketplace_buyer_sessions_token (token_hash),
  KEY idx_marketplace_buyer_sessions_expiry (expires_at)
);
