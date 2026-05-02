-- Self-service signup: verify email before activating account (INVITED → ACTIVE).

CREATE TABLE email_verification_tokens (
  id          CHAR(36) PRIMARY KEY,
  user_id     CHAR(36) NOT NULL,
  token_hash  CHAR(64) NOT NULL,
  expires_at  TIMESTAMP NOT NULL,
  used_at     TIMESTAMP NULL,
  UNIQUE KEY uq_email_verif_token_hash (token_hash),
  CONSTRAINT fk_email_verif_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_email_verif_user_unused ON email_verification_tokens (user_id, used_at);
