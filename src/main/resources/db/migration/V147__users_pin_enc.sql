-- Recoverable admin copy of staff till PINs (AES via CredentialEncryptionService).
-- Auth still uses pin_hash (bcrypt). Existing users keep pin_hash only until PIN is reset.
ALTER TABLE users
    ADD COLUMN pin_enc VARCHAR(512) NULL AFTER pin_hash;
