<div align="center">

# Phase 16 — Web cart, checkout & payments (charter)

### **Goal:** Turn the Phase 15 **storefront window** into a **transactional** web channel—cart through payment—without duplicating catalog or pricing truth.

[![Phase](https://img.shields.io/badge/phase-16-planned-blue)](./README.md)

</div>

---

## Relationship to Phase 15

| Phase 15 (done) | Phase 16 (this document) |
|-----------------|---------------------------|
| Public read API, `/shop`, PDP, SEO | **Write** paths: cart, checkout session |
| Branch-scoped **display** prices | **Reservation** / stock policy at fulfillment branch |
| Soft CTA (WhatsApp, visit store) | **PSP** integration, order confirmation |

---

## Product scope (v1 proposals)

### Cart

- **Guest cart** persisted in browser (encrypted local storage or HTTP-only BFF cookie) with server-side **cart id** for TTL and abuse controls.
- **Optional logged-in** cart merge (reuse `customers` or web identity later).
- Line items reference **item id** + **quantity**; price **re-validated** server-side from catalog branch selling prices before checkout.

### Checkout

- **Fulfillment mode:** pickup at **catalog branch** (Phase 15 default) vs explicit branch picker (if product agrees).
- **Time slots** (optional v1): simple “pickup windows” table or “we’ll call you”.
- **Contact capture:** name, phone, email (minimal for pickup); align with GDPR / consent copy.

### Payments

- **M-Pesa STK Push** or **card** via single PSP abstraction (`PaymentIntent` state machine).
- **Webhook** idempotency (pattern exists for other webhooks in monolith).
- **Failure / timeout** UX and reconciliation job.

### Orders

- **Option A:** New **`web_orders`** aggregate with `channel = web`, lines, payment status.
- **Option B:** Bridge to existing **`sales`** with `channel = web` and restricted lifecycle—only if domain experts sign off to avoid POS invariant breakage.

### Trust & fraud

- **Bot protection:** CAPTCHA or edge challenge on checkout submit.
- **Stock reservation:** short TTL hold at catalog branch (or pessimistic “oversell risk” flag until reservation ships).

---

## Non-goals (Phase 16)

- Multi-currency checkout beyond business currency.
- Subscriptions / recurring.
- Full **delivery** logistics (courier integration) — pickup-first unless explicitly added.

---

## Dependencies

- Phase 15 public catalog + slug resolution.
- Existing **selling_prices**, **items**, **branches**, **businesses.settings**.
- Email/SMS for receipts (reuse Mailgun or add provider).

---

## Exit criteria (draft)

- [ ] Guest can add published SKU to cart, see branch-accurate **line prices**.
- [ ] Checkout collects required contact fields and creates a **paid or pending** order record.
- [ ] Webhook + idempotency verified under replay.
- [ ] Phase 17+ items (returns, accounting posts) **chartered** separately.

---

## Related

- [Phase 15 — Storefront window](./PHASE_15_PLAN.md)
- [Phase 4 — POS / sales](./PHASE_4_PLAN.md)

---

*Charter only — implementation planning happens after Phase 15 definition-of-done sign-off.*
