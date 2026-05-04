# Database backup and restore (Phase 8 Slice 4)

The application can produce **encrypted** database artefacts using the native tools **`mysqldump`** (MySQL / MariaDB) or **`pg_dump`** (PostgreSQL), then wrap the output with **AES-256-GCM** (passphrase-derived key). Artefacts are stored either under a **local directory** or in **S3-compatible** object storage (AWS S3, MinIO, etc.).

## Configuration (high level)

| Property | Purpose |
|----------|---------|
| `app.integrations.backup.enabled=true` | Registers the backup orchestrator and storage beans. |
| `app.integrations.backup.encryption.passphrase` | **Required** to run a backup; used for PBKDF2 + AES-GCM. Set via `BACKUP_ENCRYPTION_PASSPHRASE` in production. |
| `app.integrations.backup.local-dir` | If set, encrypted files are written here instead of S3. Useful for development or air-gapped staging. |
| `app.integrations.backup.s3.*` | Bucket, region, optional endpoint/path-style for MinIO, and credentials or default credential chain. |
| `app.integrations.backup.scheduler.enabled` | When `true` (and orchestrator is on), runs backups on the configured cron. |
| `app.integrations.backup.retention-days` | After a successful upload, objects under the object prefix older than this many days are deleted (best-effort). |

Full defaults live in `application.properties`.

## Operational notes

- **Multi-tenant single database:** one logical dump contains **all tenants**; tenant-level export is a **GDPR** concern (Phase 8 Slice 5), not this job.
- **`mysqldump` / `pg_dump`:** must be on the server **`PATH`** as seen by the JVM process (typical on a DB host or app container that includes client tools).
- **Credential source:** the job reads the JDBC URL and credentials from the app’s **Hikari** `DataSource` (same as the running application).
- **Metrics:** Micrometer gauge `backup.last.success.epoch.seconds` is updated after each successful run (0 means never succeeded).
- **Audit:** each run is recorded in the `backup_runs` table (status, engine, storage key, sizes, SHA-256 of ciphertext, errors).

## File format

Encrypted files start with the ASCII magic **`UBBK1`**, followed by salt (16 bytes), GCM nonce (12 bytes), then ciphertext including the GCM authentication tag. Keys are derived with **PBKDF2-HMAC-SHA256** (see `BackupEncryptionService` for iteration count).

## Restore outline

1. Download the `.enc` object from S3 or copy from local backup dir.
2. Provide the **same passphrase** as `app.integrations.backup.encryption.passphrase` / `BACKUP_ENCRYPTION_PASSPHRASE`.
3. Decrypt to a scratch file (implement a small Java helper calling `BackupEncryptionService.decryptFile`, or reuse the same AES logic in a trusted admin tool).
4. **PostgreSQL:** `pg_restore -Fc -d target_db decrypted.dump` (use `-Fc` if the plaintext was custom format from `pg_dump -Fc`).
5. **MySQL:** `mysql -h host -u user -p target_db < decrypted.sql`.
6. On a staging instance, run **`ANALYZE`** on heavy tables after restore, then smoke-test the application against that database.

Recovery time objective (RTO) and retention are organization policy; this job provides **durable encrypted blobs** and metadata only.
