# Desktop log reporting — setup guide

Desktop installs ship tails of their local logs (`kiosk.log`,
`backend.out.log`, `backend.err.log`, `mariadb.log`) to the platform **when
the machine happens to be online**. They show up in
**Super Admin → Platform → Logs** (the *Desktop install logs* panel).

Reporting is fire-and-forget: 5 s timeouts, never blocks the till, and is
completely inert until an ingest key is configured on both sides.

```
Kiosk Desktop (till)                Platform (kiosk.ke)
─────────────────────               ────────────────────
DesktopLogReporter  ── POST ──►     POST /api/v1/platform/desktop-logs
(every 6h + 2 min                 (X-Desktop-Log-Ingest-Key header,
 after boot, only                    gzip multipart bundle)
 when online)                      │
                                   ├─ S3 object:  desktop-logs/{installId}/{ts}-{id}.gz
                                   └─ index row:  desktop_log_uploads
                                                    (listed in the console)
```

## 1. Generate the shared ingest key (one time)

```bash
openssl rand -base64 32
```

The same value is used on the platform **and** on every till you want to
report. (Example used in this repo's test runs: `XRVdyPlVZj1j+YwVEKozrymTc2escqHxgyGF2BxWP8E=`)

## 2. Platform side (Coolify / Docker on kiosk.ke)

1. **Deploy the updated backend jar** from `backend/` (contains the ingest
   endpoint + migration `V224__desktop_log_uploads.sql`, which Flyway applies
   automatically on deploy).
2. Add to the backend runtime env (Coolify → Java service → Env):
   ```bash
   APP_DESKTOP_LOG_INGEST_KEY=<the key from step 1>
   ```
3. **Storage** — the ingest reuses the backup S3 env vars:
   ```bash
   # If backups (app.integrations.backup) already use S3 on this deployment,
   # STORAGE_BUCKET is already set and nothing else is needed.
   STORAGE_BUCKET=<bucket>            # object storage bucket
   STORAGE_ACCESS_KEY=<access key>    # optional if instance role / IAM
   STORAGE_SECRET_KEY=<secret key>
   # MinIO/custom endpoint only:
   STORAGE_ENDPOINT=https://minio.example.com
   STORAGE_PATH_STYLE=true
   ```
   (For a local/dev API you can instead set `DESKTOP_LOG_INGEST_LOCAL_DIR`
   to a folder; do **not** use that on prod — container storage is ephemeral.)
4. Redeploy / restart the API. Verify the endpoint is armed:
   ```bash
   curl -s -o /dev/null -w "%{http_code}\n" -X POST \
     -H "X-Desktop-Log-Ingest-Key: wrong" \
     -F installId=x -F "log=@/dev/null" \
     https://kiosk.ke/api/v1/platform/desktop-logs
   # → 401 (armed). Without the header on an unconfigured server it returns 503.
   ```

## 3. Till side (each Windows/macOS install)

No reinstall needed if the till already runs a build that includes the
reporter (any installer published after the log-reporting feature landed).
Two ways to give the till the key:

**Option A — key file (simplest, survives reinstalls):**
```powershell
# On the till PC, as the user who runs Kiosk:
$conf = Join-Path $env:APPDATA "Palmart\conf"
New-Item -ItemType Directory -Force -Path $conf | Out-Null
Set-Content -Path (Join-Path $conf "log-ingest-key") -Value "<the key from step 1>" -NoNewline
```

**Option B — environment variable** (forwarded by the launcher to the JVM):
```
APP_DESKTOP_LOG_INGEST_KEY=<the key from step 1>
```

Then restart Kiosk Desktop. The first bundle goes out ~2 minutes after boot
if the machine has internet; afterwards every 6 hours. Success/failure is
visible in `%APPDATA%\Palmart\backend.out.log`:
`Desktop log bundle sent to …` / `rejected …` / `failed (offline?)`.

## 4. Viewing

Open **Super Admin → Platform → Logs** (https://kiosk.ke/super-admin/platform/logs).
Each row is one bundle: uploaded time, install id, version, size — with
**View** (decompresses in the browser) and **Download**.

## 5. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Panel says no bundles | Till never online at report time (normal for offline shops); key not set on till; or cloud not deployed with the ingest endpoint (`403` on the endpoint means old backend — redeploy) |
| `401 rejected` in backend.out.log | Key mismatch between till and cloud |
| Bundles appear but no `installId` correlation | Expected — install ids are per-machine UUIDs shown in the panel |
| Want more frequent reports | Set `APP_DESKTOP_LOG_REPORTING_INTERVAL_MS` (ms) on the till |

## Notes

- The bundle is **tails only** (512 KB per log file, configurable via
  `APP_DESKTOP_LOG_REPORTING_TAIL_BYTES`), so payloads stay small.
- Logs may contain business data (product names, request paths). The ingest
  key is the only gate; keep it secret and rotate it by changing it in both
  places.
- `installId` lives in `%APPDATA%\Palmart\conf\install-id` (created on first
  report) and identifies a machine across reinstall-bundles.
