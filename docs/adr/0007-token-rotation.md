# ADR 0007 — Refresh token rotation and reuse detection

## Status

Accepted (Phase 1, Slice 3)

## Context

The API uses short-lived JWT access tokens and longer-lived refresh tokens. Refresh tokens are stored only as hashes in `user_sessions`. If a refresh token is stolen, an attacker can obtain new access tokens until the refresh expires or is revoked.

Without rotation, a single stolen refresh token remains valid for the full TTL. With rotation but without reuse detection, the legitimate user and attacker can both refresh indefinitely from two independent session rows.

## Decision

1. **Rotate on every successful refresh** — Each `POST /api/v1/auth/refresh` mints a new access/refresh pair and persists a new `user_sessions` row. The previous row is set `revoked_at` and `rotated_to` points at the new session id.

2. **Reuse detection** — If a client presents a refresh token whose session row already has `revoked_at IS NOT NULL`, treat this as a possible theft or replay: **revoke all active sessions for that user** (bulk update), return `401`, and require a fresh login. The bulk revocation runs in a **separate transaction** (`REQUIRES_NEW`) so it commits even when the request ends in `401`, avoiding a rollback that would leave stolen rotations usable.

3. **Access JWT `jti`** — Each access token carries a `jti` matching `user_sessions.access_token_jti`. The JWT filter rejects tokens whose session row is missing, revoked, or expired, and rejects users who are not `ACTIVE` or are soft-locked.

## Consequences

- Legitimate user and attacker cannot both keep refreshing after a fork; replay of an old refresh invalidates the whole session family for that user.
- Users must sign in again after reuse is detected (intentional friction).
- Parallel double-refresh of the *same* token is still a race: one wins; the loser may hit reuse logic — acceptable for Phase 1; stricter serialization can be added later if needed.

## References

- `docs/PHASE_1_PLAN.md` §3.2–3.3
- `zelisline.ub.identity.application.AuthService#refresh`
- `zelisline.ub.identity.application.UserSessionRevocation`
