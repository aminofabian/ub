# Outbound webhooks — operations

Tenant admins manage subscriptions under `POST /api/v1/integrations/webhooks` (permission `integrations.webhooks.manage`). Deliveries are signed (`WebhookSigner` header); retries and DLQ behaviour are configured via `app.integrations.webhook.delivery.*`.

## SSRF guard (Phase 8 Slice 6)

When **creating** or validating a subscription URL, the server rejects hosts that point at obvious internal surfaces:

- **Link-local** ranges (including IPv4 `169.254.0.0/16`, e.g. cloud metadata `169.254.169.254`).
- **Loopback** and **RFC1918** private ranges — unless explicitly allowed for development.
- Hostname **`metadata.google.internal`**.

| Property | Purpose |
|----------|---------|
| `app.integrations.webhook.allow-loopback-and-rfc1918-targets` | Set `true` only in dev/staging if subscribers run on `localhost` or LAN IPs (default `false`). |
| `app.integrations.webhook.ssrf.resolve-hostnames` | When `true`, DNS A/AAAA records must not resolve to blocked addresses (default `true`). |
| `app.integrations.webhook.ssrf.resolve-timeout-ms` | Cap on DNS resolution during validation (default `1500`). |

**Integration tests** that persist subscriptions **without** HTTP validation may still use loopback URLs by writing rows directly (see `WebhookOutboundIT`).

## Subscriber checklist

- Endpoint **HTTPS** in production.
- Verify **HMAC** signature and reject replays (timestamp skew policy is subscriber-side).
- Return **2xx** quickly; use a queue if processing is slow.

## Operational tuning

- Disable noisy subscriptions (`DELETE`) instead of letting them burn retry budgets.
- Replay **dead** deliveries from the admin UI only after fixing the subscriber.

## OWASP ZAP

Automated DAST (e.g. ZAP baseline) against this API is tracked for **Phase 11** per `PHASE_8_PLAN.md`; run an internal smoke checklist before exposing webhooks to untrusted partners.
