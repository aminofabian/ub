# ADR 0010 — Public payment claim is token-only, channel-typed, and rate-limited

* Status: Accepted (Phase 5)
* Deciders: Phase 5 reviewers
* Date: 2026-05-04

## Context

Phase 5 Slice 6 introduces a public link customers can use to attest that they paid down a tab
out-of-band (cash dropped at the counter, M-Pesa direct, etc.). The plan asks two open
questions:

> **Public claim** — require **logged-in customer** (magic link) vs **fully anonymous** with
> phone verify? *(`PHASE_5_PLAN.md`, Open question 4)*
>
> Approve: creates `credit_transactions(payment)` + journal (Cr AR, **Dr cash/Mpesa per actual
> settlement** — ADR).

The first cut of Slice 6 took both shortcuts: it shipped a `submitByBusinessAndPhone` fallback
that anyone with a business id and customer phone could call without a token, and it
hard-coded the approval debit to `1020 M-Pesa Clearing` regardless of how the customer
actually paid.

## Decision

### 1. Submission requires a token, period.

Only `POST /api/v1/public/credits/payment-claims/{plaintextToken}` accepts a submission. Tokens
are 24 random bytes hex-encoded, hashed with SHA-256 at rest. The previous
`POST /api/v1/public/credits/payment-claims/by-phone/{businessId}/{phone}` endpoint and the
companion service method are removed.

Rationale: a phone number is a public identifier, easy to enumerate. Without a token, anyone
who guesses a customer's number can flood the admin queue with bogus claims and DoS the
review pipeline. The plan's Question 4 listed two options (magic link vs fully-anonymous-with-OTP);
both require infrastructure (SMS OTP) that is not in scope for Phase 5. The smallest
correct shape is **admin-issued token only** until SMS OTP lands in Phase 7.

If a customer later loses the token, the issuance flow (`POST
/api/v1/customers/{id}/payment-claims`) is admin-gated by `credits.claims.issue`, so a fresh
token can always be re-issued from the back office without exposing a public re-issue path.

### 2. Approval requires an explicit settlement channel.

`POST /api/v1/credits/payment-claims/{claimId}/approve` now requires
`{"channel": "cash"|"mpesa"}` in the body. The service routes the journal accordingly:

| `channel` | Debit                 | Credit                              |
|-----------|-----------------------|-------------------------------------|
| `cash`    | `1010 Operating Cash` | `1100 Accounts Receivable Customers` |
| `mpesa`   | `1020 M-Pesa Clearing` | `1100 Accounts Receivable Customers` |

`CreditClaimChannels.isValid` is the single source of truth; controller validation rejects
anything else with 400.

A second approve with the same `claimId` is a silent no-op (preserved §14.8 behaviour). A
second approve with a different channel is also a no-op — once approved, the claim is closed.

### 3. Reject path exists.

`POST /api/v1/credits/payment-claims/{claimId}/reject` with optional `{"reason": "..."}`
moves an `issued`/`submitted` claim to `rejected`. Rejecting an already-approved claim is a
409. Re-rejecting is a no-op. No GL movement.

### 4. Public submissions are rate-limited.

`PublicCreditClaimRateLimitFilter` applies a 10-per-minute-per-IP sliding window to all
`POST /api/v1/public/credits/**` requests. The cap is configurable via
`app.security.public-credit-claim-rate-limit-per-minute`. Catalog GETs continue to use the
existing `PublicStorefrontRateLimitFilter` with its own (higher) limit.

## Consequences

### Positive

* The unauth surface area shrinks to "must possess a token". This is a credential check, not
  a directory-lookup check.
* Approval journals match reality. Phase 6 cash-position reports get the right asset.
* Rate limiter blocks bulk-claim spam at the filter layer before any DB or auth machinery
  runs.

### Negative / Trade-offs

* Customers without a token can't self-serve. They must contact the shop, who re-issues a
  link from the back office. This is acceptable for Phase 5 because the volume is small and
  the workflow is admin-driven; Phase 7 may revisit with SMS OTP.
* Approval requires one more click in the admin UI (channel selector). Worth it to keep the
  ledger honest.

### Migration

* No DB migration needed for the channel parameter (existing approved-claim journals were all
  posted to `1020`, which remains a valid value).
* The phone-fallback endpoint is removed; any external callers depending on it must migrate
  to the token endpoint. None known to exist outside dev tools.

## References

* `backend/docs/PHASE_5_PLAN.md` — Slice 6, Cross-cutting "Rate-limit public claim".
* `backend/src/main/java/zelisline/ub/credits/CreditClaimChannels.java`
* `backend/src/main/java/zelisline/ub/credits/application/PublicPaymentClaimService.java` —
  `approve(businessId, claimId, channel)` and `reject(businessId, claimId, reason)`.
* `backend/src/main/java/zelisline/ub/platform/security/PublicCreditClaimRateLimitFilter.java`
