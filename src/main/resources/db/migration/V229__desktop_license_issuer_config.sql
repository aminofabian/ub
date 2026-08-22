-- Super Admin → Platform → Desktop licenses: console-managed signing key.
--
-- Single-row table holding the vendor Ed25519 private key (encrypted at rest
-- via CredentialEncryptionService) so license issuance can be configured from
-- the console without touching the deployment environment. The public key is
-- stored plaintext for display only — the till verifies against the copy baked
-- into the desktop JAR (app.desktop.license.public-key).

CREATE TABLE desktop_license_issuer_config (
  id              CHAR(36)     PRIMARY KEY,
  private_key_enc TEXT         NOT NULL,
  public_key      VARCHAR(256) NULL,
  updated_at      TIMESTAMP(6) NOT NULL
);
