# Integration API keys — operations

Phase 8 Slice 1 introduced tenant-scoped API keys (`kpos_…`) resolved by `X-API-Key` or `Authorization: Bearer kpos_…`. Slice 6 adds **per-IP throttling** after repeated **invalid** keys.

## Rate limits

| Control | Property | Default |
|--------|----------|---------|
| Successful auth — requests/minute **per key id** | `app.integrations.api-key.requests-per-minute` | `120` |
| Failed auth — attempts/minute **per client IP** | `app.integrations.api-key.invalid-attempts-per-minute-per-ip` | `40` |

After too many invalid attempts from one IP, the API returns **429** with `Retry-After: 60`. Valid keys clear the failure window for that IP.

Client IP is taken from `X-Forwarded-For` (first hop) when present, otherwise `request.remoteAddr`. Only trust this header when your edge strips unknown values.

## Rotation playbook

1. **Create** a new key in-app with the least scopes needed.
2. Update consuming automation to use the new secret (deploy config / secrets manager — never commit raw keys).
3. Verify traffic on the new key (staging first).
4. **Revoke** the old key after the cut-over window.
5. Audit `activity_log` / monitoring for spikes of **401** on integration routes after rotation.

## Secrets hygiene

- Keys are stored **hashed** server-side; the plaintext is shown **once** at creation.
- Prefer short TTL automation credentials and environment-specific keys per tenant.
- Repository CI runs `./gradlew test` when `backend/` changes (`.github/workflows/backend-ci.yml`). Layer Dependabot or OWASP Dependency Check separately if you need automated CVE alerts on libraries.
