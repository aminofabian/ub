# GDPR / Kenya DPA — ops checklist (Phase 8 Slice 5)

Use this as an **internal** control before you treat a deployment as “privacy-ready”. It is **not** legal advice; align wording and retention with counsel for your jurisdiction.

## Data inventory

- [ ] **Tenants** understand that **full-database backups** (Phase 8 Slice 4) may contain **all** businesses in a single artefact unless you operate per-tenant databases.
- [ ] **Customer** PII fields are documented (`customers`, `customer_phones`).
- [ ] **Staff** PII fields are documented (`users`; passwords/PINs are hashes only).
- [ ] **Exports** list what is included: see ZIP `manifest.json` (`ub-privacy-export-v1`).

## Subject access (export)

- [ ] Only roles with **`integrations.privacy.manage`** can queue downloads or run erasure (see seeded permissions / role matrix).
- [ ] Download links use a **random token** and **expire** (default one hour in implementation); treat tokens like secrets in logs and support tickets.
- [ ] **Staff export** omits password/PIN material; only `hasPassword` / `hasPin` flags are included.

## Erasure / anonymisation

- [ ] **Customers**: phones removed; name/email/notes cleared; **`customers.id` and `sales.customer_id` unchanged** so financial history stays consistent.
- [ ] **Users**: sessions **revoked**; name/phone scrubbed; email replaced with `redacted.{userId}@invalid.ub`; credentials cleared; user **suspended**; **`anonymised_at`** set. **The last active owner** cannot be anonymised (409 — operational safety).
- [ ] Process for **rejecting** erasure where **lawful retention** applies (e.g. tax ledgers) is agreed with counsel.

## Optional organisational controls (not automated in v1)

- [ ] **Four-eyes approval**: for high-risk tenants, require a second approver (owner / DPO) **before** calling anonymise endpoints — track in ticketing or change management.
- [ ] **Register of processing** / **DPIA** touchpoints updated when you add new PII fields or integrations.

## Verification

- [ ] Run **`./gradlew test`** after privacy-related changes; keep **`PrivacySlice5IT`** green.
- [ ] After a restore from backup in staging, confirm exports and anonymisation still behave as expected.
