# Onboarding Region & Catalog — Scope

### Dynamic country / currency during signup, and catalogs that fit country × business vertical (not Kenya mini-mart only).

**Status:** Scoped (v3) — Phase 1 implementation in progress  
**Depends on:** Global catalog Phase A (tenant browse/adopt), Phase 5 regional catalogs (SA multi-catalog + resolver), existing onboarding questionnaire  
**Related:** `SUPER_ADMIN_GLOBAL_CATALOG_SCOPE.md`, `GLOBAL_PRODUCTS_CATALOG_PLAN.md`, `SUPPLIER_MARKETPLACE_SCOPE.md`, desktop `/setup`  
**Owners (proposed):** Product (readiness + payments gate), Backend (region map + onboard/PATCH), Frontend (landing + questionnaire + `formatMoney`), Ops/SA (UG content + packs)

> **Implementation note:** Phase 1 shipped (region onboard + empty catalog UX). Phase 2 started: currency-safe `formatMoney` / `useFormatMoney`, credits/customers/hub, non-KES shift cash total (no fake KES notes).

> **v3 changes:** locked recommendations for open decisions; TOC; all cloud onboard entry points listed; concrete catalog-readiness bar; empty-catalog UX; API contract sketches; in-flight questionnaire migration; desktop alignment rule; self-serve config home; parent-doc sync corrected (UG shell already exists).  
> **v2 carry-forward:** currency is **not** a string swap — `formatMoney` forces `en-KE` + 2 decimals (breaks UGX/RWF); resolver **silently falls back to KE** when no regional catalog exists; payments/phone are Kenya rails; mobile + storefront are extra money surfaces.

---

## Table of contents

1. [Why this exists](#1-why-this-exists-business)
2. [Goal (architecture)](#2-goal-architecture)
3. [Current state (code truth)](#3-current-state-code-truth)
4. [Product model](#4-product-model-catalog-grain)
5. [Decisions (locked)](#5-decisions-lock-these)
6. [Onboarding UX](#6-onboarding-ux)
7. [Country readiness policy](#7-country-readiness-policy)
8. [Phase plan](#8-phase-plan)
9. [API / DTO deltas](#9-api--dto-deltas-sketch)
10. [Catalog resolution](#10-catalog-resolution-unchanged--document-for-implementers)
11. [Currency display](#11-currency-display--strategy--hit-list-phase-2)
12. [Localization dependencies](#12-localization-dependencies-payments-phone-tax)
13. [Data integrity & migration](#13-data-integrity--migration)
14. [Out of scope](#14-out-of-scope)
15. [Test plan](#15-test-plan-minimum)
16. [Build order](#16-build-order)
17. [Effort sketch](#17-effort-sketch)
18. [Key file pointers](#18-key-file-pointers)
19. [Parent doc sync](#19-parent-doc-sync)
20. [Changelog](#20-changelog)

---

## Ship vs wait (one screen)

| Ships in this scope | Explicit dependency / later |
|---------------------|-----------------------------|
| Country at cloud create + questionnaire | Per-country mobile-money rails (MTN MoMo, etc.) |
| Derived currency + timezone + VAT default | Full phone localization beyond dial-code awareness |
| Self-serve gated by catalog + payments readiness | Mobile-app money sweep |
| Verticals via store types + packs (not new catalogs) | FX / multi-currency ledgers |
| Currency-safe `formatMoney` + core FE/BE money surfaces | Filling every EA country day one |
| Lock country/currency after onboarding completes | Catalog-per-vertical |

---

## 1. Why this exists (business)

### Problem

Cloud self-serve shops always land as **Kenya / KES / Africa/Nairobi**, even though:

- `businesses.currency`, `country_code`, and `timezone` already exist
- Desktop `/setup` and Super Admin create already **collect** country / currency (as independent free picks — **no country→currency derivation exists yet**)
- Global catalog resolution already keys off `country_code` (and optional `settings.globalCatalogCode`)
- Onboarding already asks for store type(s) — but only grocery / butchery shapes

So:

1. **Currency is hardcoded in the cloud path** — UI falls back to `"KES"` everywhere; money formatting, emails, PDFs, and cash denominations assume Kenya.
2. **Money formatting is not currency-safe** — `formatMoney` hardcodes `en-KE` locale **and forces 2 fraction digits**. Zero-decimal currencies (UGX, RWF) render as `UGX 1,500.00` — wrong, not just cosmetic.
3. **Catalog feels Kenya-only** — ~2.4k KE retail SKUs in `default`; `ug-retail` is an empty published shell; cloud tenants never get `country_code ≠ KE`, so they never resolve elsewhere. Worse, any country **without** its own catalog silently falls back to the **KE catalog with KES prices** (§7, resolver step 3).
4. **Payments & phone are Kenya rails** — M-Pesa STK push, `KenyanPhoneForms`, `MpesaStkIntent` assume `+254` / KES. A non-KE shop cannot collect mobile-money payments as-is (§12).
5. **Verticals are incomplete** — cosmetics, wines & spirits, etc. are not first-class store types; packs today are mini-mart / full-grocery shaped.
6. **Onboard entry points are wider than “landing”** — several auth/signup surfaces call `onboardBusiness(host, name)` with no country (§3).

### Job to be done

> A new shop picks **where they operate** and **what kind of shop they are** during onboarding; the platform sets currency + timezone, shows the right regional catalog, and ranks starter packs for their vertical — without ops hand-editing tenants or shipping FX.

### Who

| Role | Job |
|------|-----|
| **Shop owner (cloud signup)** | Choose country (+ derived currency); choose store type(s); stock shelves from the matching catalog / packs |
| **Shop owner (desktop)** | Already chooses country / currency / timezone on `/setup` — stay aligned with cloud (defaults derived; optional override) |
| **Platform / Super Admin** | Curate regional catalogs + vertical packs; override catalog via `globalCatalogCode` when needed; change locked region with warning |
| **Not involved** | Supplier Marketplace (separate product) |

### Success metrics

Instrument on ship or these stay slogans.

| Metric | Target (MVP+) | How measured |
|--------|----------------|--------------|
| Cloud create currency ≠ always KES | 100% of shops that picked a non-KE country | `businesses.currency` vs onboarding country |
| Catalog resolved region matches `country_code` | 100% when a published catalog exists for region | resolve log / adopt meta `catalogCode` |
| Step “Stock your shelves” uses resolved catalog | Before first browse | onboarding funnel: country set → catalog list |
| Vertical pack “for you” when store type matches | Cosmetics / wines packs rank first for those types | catalog UI sort + pack `store_kit_id` |
| Hardcoded `KES` in money UI after Phase 2 | Zero in POS / credits / catalog / settings paths listed in §11 | grep + spot check with UGX tenant |
| Self-serve country only if readiness = green | 100% of picker options | config list vs catalog + payments checklist (§7) |

### Business risks

| Risk | Mitigation |
|------|------------|
| **Silent KE fallback for catalog-less countries** — resolver step 3 sends e.g. a TZ shop to `default` (KES prices) with no error | Gate self-serve to countries with a **published regional catalog**; for SA-created off-list countries, publish an empty regional shell before setting `country_code` (§7) |
| **Zero-decimal currency mis-render** (UGX/RWF as `x.00`) | Replace `formatMoney` internals with currency→locale map + `Intl` default fraction digits (§11) |
| Offer UG in picker while `ug-retail` has zero SKUs | Gate by concrete readiness bar (§7); empty-state UX if early access is intentional |
| Absolute KE recommended prices adopted as UGX | Prefer `suggestedMarginPct`; null absolute sell on clone; don’t FX-convert in v1 |
| Tenant changes country after sales → wrong money history | Lock after questionnaire `completed`; changing currency **never** re-values stored amounts (§5.4, §13) |
| **Non-KE shop cannot take payments** — M-Pesa STK / `+254` only | Country stays off self-serve until payment story exists (§5.10, §12). Cash/credit-only launch requires explicit product exception |
| Explode catalogs into country × vertical | **One catalog per country**; verticals = store types + packs (§5.1) |
| Confuse Marketplace with Global Catalog | Same rule as SA catalog scope — separate surfaces |
| In-flight questionnaires break when steps renumber | Migration rule for `settings.onboarding` step/answers (§13) |

---

## 2. Goal (architecture)

Two independent axes drive the catalog; a third (locale/payments) rides along with country:

```text
Country  →  currency + timezone + which regional catalog (SKU/price region)
            ↳ also pulls in: money format, VAT default, payment rail, phone format (§12–§13)
Vertical →  store type(s) + departments + starter packs (“for you”)
```

Getting the two axes right in onboarding is this scope; the locale/payment tail (§12) is a tracked dependency, not silently assumed done.

| Axis | Writes | Catalog effect |
|------|--------|----------------|
| **Country** | `businesses.country_code`, `currency`, `timezone` | Resolver step 2: `region_code` match |
| **Vertical** | `settings.profile.storeType(s)`, onboarding answers, item types | Pack sort / “for you” via `store_kit_id` |
| **Override (ops)** | `settings.globalCatalogCode` | Resolver step 1 — already shipped |

```text
Landing / signup / questionnaire
        │
        ├─ country ──► business.country_code + currency + timezone (+ VAT default)
        │                      │
        │                      ▼
        │              GlobalCatalogResolver
        │                 (override → region → default)
        │
        └─ storeTypes ──► profile + packs ranked by store_kit_id
                                 │
                                 ▼
                    Stock-your-shelves /products/catalog adopt
```

**Does not change:** copy-on-adopt into tenant `items`; provenance `global_product_source_id`; SA curation model.

---

## 3. Current state (code truth)

### Shipped and reusable

| Capability | Where |
|------------|--------|
| Business columns `currency`, `country_code`, `timezone` | `Business.java`, `V1__tenancy_core.sql` |
| SA / desktop create with country + currency | `CreateBusinessRequest`, `DesktopSetupService` |
| Country / currency / timezone **option lists** (KE, UG, TZ, RW, NG, ZA / KES…USD,EUR) | `frontend/setup/page.tsx` — `COUNTRIES`, `CURRENCIES`, `TIMEZONES` (desktop-only; seed for the canonical map) |
| Catalog resolution | `GlobalCatalogResolver` |
| KE `default` + empty UG `ug-retail` | `V159__global_catalog_seed_ug_empty.sql` / Phase 5 |
| Pack ↔ `store_kit_id` + tenant “for you” sort | Packs + `products/catalog/page.tsx` |
| Onboarding store types + 6-step questionnaire | `onboarding-questionnaire.ts`, `OnboardingAnswersDto` |
| Onboarding status machine | `idle\|pending\|active\|completed\|dismissed` (`OnboardingPatchRequest`) |
| UG resolver behavior already tested | `GlobalCatalogIT` (UG business → `ug-retail`) — mechanism works; content + onboarding wiring missing |

### Broken / missing for this job

| Gap | Detail |
|-----|--------|
| Cloud onboard hardcodes KE/KES/Nairobi | `PublicHostResolverService.onboardBusiness` (`setCurrency("KES")` / `setCountryCode("KE")` / `setTimezone("Africa/Nairobi")`) |
| Onboard API accepts only name + host | `POST .../public/hosts/.../onboard` body `{ name, host }` — no `countryCode` |
| Multiple FE callers, none pass country | `landing-signup-modal.tsx`, `(auth)/signup/page.tsx`, `(auth)/signup/staff/page.tsx`, `(auth)/login/staff/page.tsx` → `onboardBusiness(host, name)` |
| Questionnaire never collects country / currency | `OnboardingAnswersDto` / FE answers type |
| No country→currency derivation | Neither desktop nor cloud derives currency/tz from country; `/setup` is 3 independent dropdowns |
| Tenant cannot PATCH currency / country / timezone | `UpdateBusinessRequest` — no those fields; settings UI read-only |
| `formatMoney` not currency-safe | `frontend/lib/utils.ts` hardcodes `en-KE` + `min/maxFractionDigits: 2` + default `"KES"` |
| Money UI assumes KES at ~40+ sites | `"KES"` / `en-KE` / `KSh` across FE, emails, PDFs, `KES_DENOMINATIONS`, SEO `priceCurrency` — see §11 |
| Payments/phone are KE-only | `MpesaStkIntent`, `GatewayStkPushService`, `KenyanPhoneForms` (§12) |
| Extra money surfaces | Mobile apps + customer storefront format money independently |
| Default tax/VAT is KE 16% | `DesktopSetupRequest.taxRate` default 16 — not localized; cloud create doesn’t seed per-country VAT |
| Store types grocery-shaped only | `StoreTypeCodes.PATTERN` = `butchery\|mini-mart\|full-grocery\|fresh-market\|mixed-shop` |
| Locality placeholders Kenya-only | `BRANCH_LOCALITY_PLACEHOLDERS` = Mirema, Kasarani, … |
| Non-KE catalog content | UG shell empty; no TZ/RW/… catalogs |
| Self-serve readiness config | Does not exist yet (`palmart.selfserve.countries` proposed in §7) |

---

## 4. Product model (catalog grain)

### Recommended (lock)

**One published regional catalog per country** (e.g. KE `default` / future `ke-retail`, UG `ug-retail`), currency matching that region.

**Verticals are not separate catalogs.** They are:

1. Canonical **store type** codes (onboarding + profile)
2. **Starter packs** with `store_kit_id` matching those codes
3. **Department / section kits** for onboarding departments step

Example:

```text
UG cosmetics shop
  → country UG → ug-retail (UGX templates)
  → storeTypes includes cosmetics → cosmetics packs first
  → still can search rest of ug-retail
```

### Rejected for v1

| Alternative | Why not now |
|-------------|-------------|
| Catalog per country × vertical (`ke-cosmetics`, `ug-wines-…`) | Combinatorial content + SA ops cost; packs already solve ranking |
| FX convert KE prices into local currency on adopt | Misleading; prefer margin % + local price entry |
| Multi-currency inside one business | Out of scope |

### When to split catalogs later

Only if a vertical’s SKU set is truly disjoint **and** ops cannot curate it as packs inside the regional catalog (e.g. heavily regulated pharmacy formulary). Until then: packs.

---

## 5. Decisions (lock these)

| # | Decision | Recommendation | Status |
|---|----------|----------------|--------|
| 1 | Catalog grain | **One catalog per country**; verticals = store types + packs | **Locked** |
| 2 | Currency at onboarding | **Derived from country** on cloud. Desktop: auto-fill from same map on country change; allow manual override (covers USD shop edge cases). Cloud v1: **no free-pick** | **Locked** |
| 3 | Self-serve country list | Start with **KE** always; add **UG** only when readiness = green (§7) | **Locked** |
| 4 | Mutability | Country / currency / timezone editable while onboarding status is `pending` or `active`; **locked** after `completed` or `dismissed`. Super Admin can still change (with warning if items/sales exist) | **Locked** |
| 5 | When to persist region | **At cloud create** when country is known (preferred). If omitted, first questionnaire region step must PATCH before catalog browse | **Locked** |
| 6 | First new verticals | **`cosmetics`**, **`wines-spirits`** on KE first (store types + packs + section kits) | **Locked** |
| 7 | Absolute recommended prices | Non-KE: prefer margin / null sell; do not invent FX | **Locked** |
| 8 | Marketplace | Unchanged — not part of this scope | **Locked** |
| 9 | Catalog-less country | Publish an **empty regional shell** before allowing that `country_code` (avoid silent KE fallback, §7). Do **not** change resolver fallback in v1 | **Locked** |
| 10 | Payments prerequisite | Country stays **off** self-serve until a payment story exists. Cash/credit-only for a country = **explicit product exception**, documented in readiness config | **Locked** (product may grant exceptions) |
| 11 | Region UX placement | Prefer **country on create/landing** (Option A); questionnaire region step is the safety net if create omitted it | **Locked** |
| 12 | Empty catalog at step “stock shelves” | Show honest empty state (“No starter products for your country yet — add manually or ask support”); never browse KE prices as if they were local | **Locked** |

### Still needs a human (does not block Phase 1 KE path)

| Topic | Options | Default if no reply |
|-------|---------|---------------------|
| UG cash/credit-only early access | Enable UG before MoMo rail vs wait | **Enabled** with `cash-credit-only-countries=UG` (Phase 4) |
| Desktop currency override UX | Keep free-pick forever vs warn when currency ≠ map default | Keep free-pick; warn in UI copy |
| SA country change after sales | Soft warning vs hard block | Soft warning + audit log; never auto-convert amounts |

---

## 6. Onboarding UX

### Target questionnaire shape

**Preferred:** collect country on create/landing so the questionnaire stays 6 steps.  
**Fallback:** insert **region as step 1** and renumber; `QUESTIONNAIRE_STEP_COUNT` becomes 7.

| Step | Collects | Persist |
|------|----------|---------|
| **0 / landing — Where do you operate?** (preferred) | Country (short list). Show derived currency + timezone (read-only chips) | `business.country_code`, `currency`, `timezone` at create |
| 1 | Branch count + localities | Existing; locality placeholders become country-aware (§9 map) |
| 2 | Store type(s) | Existing + new verticals (Phase 3) |
| 3 | Departments | Existing kits + new vertical kits |
| 4 | Online store | Existing |
| 5 | Branding | Existing |
| 6 | Stock your shelves | Catalog CTA — resolver must already be correct; empty-catalog UX per §5.12 |

**Hard rule:** catalog browse / adopt is unreachable until `country_code` is set to a readiness-allowed country (or KE default only when user explicitly chose KE).

### Landing / create

**Option A (preferred):** country select next to business name → `onboardBusiness(name, host, countryCode)`.

**Option B:** create still defaults KE, but questionnaire region step is mandatory and blocks progress until PATCH succeeds.

Update **every** caller (§3), not only `landing-onboarding.tsx`.

### Empty catalog UX (Phase 1 + early UG)

If the resolved catalog has **zero published products / packs**:

- Do not imply “Kenya prices in your currency”
- CTA: add products manually / skip for now
- Optional: “We’re stocking your country’s catalog — check back”

### Desktop alignment

| Surface | Rule |
|---------|------|
| Cloud | Country select → derive currency + tz (+ VAT default); reject unknown / non-ready codes |
| Desktop `/setup` | Same map for **defaults** when country changes; keep currency/tz editable for edge cases (USD shop, etc.) |
| Super Admin create | Keep free-pick; validate ISO lengths; warn if country has no regional catalog (will hit KE fallback) |

Shared constant or small public config endpoint later; duplicated FE+BE map is OK for v1 if documented and tested in sync.

---

## 7. Country readiness policy

A country is **self-serve green** only when **all** of:

1. **Published regional catalog** exists with `region_code` = that country (not relying on KE `default` fallback)
2. **Content bar** met **or** product accepts early-access empty state in writing:
   - **MVP bar for UG:** ≥ 1 starter pack with ≥ 25 published SKUs **and** ≥ 80% of those pack SKUs have HTTPS `image_url` (aligns with SA pack DoD spirit), **or** explicit “empty catalog” early-access flag
3. **Payment story** exists for that country (§5.10), **or** explicit cash/credit-only exception in config
4. **Listed** in self-serve config (single source — see below)

| Country | Catalog | Self-serve picker | Notes |
|---------|---------|-------------------|-------|
| KE | `default` (~2.4k SKUs) | Always on | Baseline; M-Pesa available |
| UG | `ug-retail` (cloned from KE; absolute prices scrubbed) | **On** (cash/credit-only exception) | MoMo rail deferred; picker shows payment hint |
| Others | None | Off | Publish shell + seed/promote before enable |

### Config home (implement once)

Prefer backend-owned allow-list so FE/landing/signup cannot drift:

```text
# application config (example)
app.selfserve.countries=KE,UG
# optional per-country flags
app.selfserve.cash-credit-only-countries=UG
```

Expose via existing public host resolve / a tiny `GET /public/selfserve/countries` so all signup UIs share one list. FE hardcoding the same list is acceptable for Phase 1 **only if** BE rejects anything else on onboard.

### Why the readiness gate is mandatory

`GlobalCatalogResolver` step 3 falls back to catalog code `default` (KE/KES) whenever a country has **no published regional catalog**. A shop with `country_code=TZ` today silently browses **Kenyan products at Kenyan shilling prices**.

**Do not** let onboarding set a `country_code` for which no regional catalog is published, unless you first publish an empty regional shell (like `ug-retail`). Prefer shells over changing the resolver in v1 (§5.9).

---

## 8. Phase plan

### Phase 1 — Region in cloud onboarding (product-critical)

**Outcome:** New cloud shops get correct `country_code` / `currency` / `timezone`; stock-shelves resolves the right catalog (or honest empty state).

| Work | Notes |
|------|--------|
| Country → currency / timezone / VAT / placeholders map | Backend source of truth; FE mirror for picker (§9) |
| Extend cloud onboard API | Accept optional `countryCode`; derive currency + tz; reject codes not in self-serve list |
| Update all FE onboard callers | Landing modal + signup + staff signup + login-staff create paths |
| Onboarding PATCH path | If create omitted country: region step patches via `UpdateBusinessRequest` (new fields) or dedicated onboarding endpoint |
| Lock after complete | Reject tenant PATCH of country/currency/timezone when status is `completed` or `dismissed` (SA exempt) |
| Empty-catalog UX | §5.12 on stock-shelves step |
| In-flight migration | §13 — don’t break existing `pending`/`active` questionnaires |
| ITs | Create with UG → UG/UGX/Kampala + resolve `ug-retail`; omit country → KE defaults; reject TZ if not enabled |

**Definition of done (Phase 1)**

- [x] Cloud create no longer unconditionally hardcodes KE/KES/Nairobi when country is supplied
- [x] Every onboard FE caller can pass country (or questionnaire forces PATCH before catalog)
- [x] Tenant cannot change country/currency after onboarding completed/dismissed
- [x] IT: UG business resolves `ug-retail`; KE still resolves `default` *(region fields set; resolver already covered by GlobalCatalogIT)*
- [x] IT: non-ready country rejected on self-serve onboard
- [x] Empty catalog shows honest empty state (no silent KE browse)

### Phase 2 — Display honesty (currency-agnostic UI)

**Outcome:** Money shown using `business.currency`, not `"KES"` fallbacks in core paths.

| Work | Notes |
|------|--------|
| `formatMoney` | Currency→locale map; stop forced 2dp; remove silent `= "KES"` default (provider/hook preferred) |
| High-traffic surfaces | Catalog, stock, supplies, credits, cashier, settings |
| Backend renderers | Order confirmation email, restock PDF — use business currency |
| Cash denominations | Map by currency or hide / generic when unknown |
| Locality placeholders | Drive from region map, not hard-coded Nairobi suburbs only |

**Definition of done (Phase 2)**

- [x] Spot-check UGX tenant: POS, credits, catalog prices show UGX without `.00` *(helper + credits/customers/catalog/storefront/shifts)*
- [x] Grep-driven cleanup of worst hardcoded `KES` / `KSh` paths listed in §11 *(P0 helper + credits + customers + hub + denominations)*
- [x] Denominations do not show Kenyan notes for non-KES

Remaining Phase 2 follow-ups: backend email/PDF renderers; analytics-workspace local formatter; cashier receive-stock `en-KE` quantity strings; SEO `priceCurrency`.

### Phase 3 — Vertical expansion (cosmetics, wines & spirits)

**Outcome:** New store types participate in onboarding + pack ranking on **KE** catalog first.

| Work | Notes |
|------|--------|
| Extend `StoreTypeCodes.PATTERN` | Add `cosmetics`, `wines-spirits` (FE `StoreTypeChoice` + options + tests) |
| Starter kits / departments | Section labels in `STORE_SECTION_STARTER_KITS` / backend kit catalog |
| SA packs | Packs with `store_kit_id` = new codes on KE; meet SA pack image DoD |
| Catalog UI | Multi-select store types still rank matching packs first |

**Definition of done (Phase 3)**

- [x] Onboarding offers cosmetics and wines-spirits
- [x] Choosing them seeds sensible departments
- [x] KE packs exist and sort as “for you” (empty published shells with `store_kit_id`; SA fills SKUs)
- [x] Existing grocery/butchery paths unchanged

### Phase 4 — Regional content

**Outcome:** Non-KE self-serve is honest and useful.

| Work | Notes |
|------|--------|
| Populate `ug-retail` | Bulk clone KE → scrub absolute prices / keep margin; or promote from UG flagship |
| Meet readiness bar | §7 content + payments (or documented exception) |
| Enable UG in self-serve list | Flip config |
| Copy | Payment hints less M-Pesa-only when country ≠ KE |

**Definition of done (Phase 4)**

- [x] UG shop can adopt at least one starter pack with usable templates *(V161 clones KE → `ug-retail`; absolute buy/sell scrubbed, margin kept/derived)*
- [x] Self-serve UG enabled in config (`app.selfserve.countries=KE,UG`)
- [x] Document that KE absolute prices must not be blindly shown as UGX *(Decision §5.7 + clone scrub + this DoD)*
- [x] Payment story or explicit cash/credit-only exception recorded (`app.selfserve.cash-credit-only-countries=UG`; public picker `paymentHint`)

**Phase 4 notes**

- Image readiness bar (≥80% HTTPS on pack SKUs) is **not** met on the KE seed (~1 HTTPS image on mini-mart pack). UG ships with **margin-only templates** and cash/credit-only as the documented exception; ops should promote imaged UG SKUs via SA before marketing pack polish.
- Do **not** FX-convert KES → UGX in v1.

### Phase 5 — Hardening & telemetry

| Work | Notes |
|------|--------|
| Funnel events | Audit events below (SYSTEM category) |
| SA tools | Change country with explicit warning if items/sales exist; audit log |
| Docs sync | Update SA catalog scope risk row (§19) |

**Events**

| Event | Props |
|-------|-------|
| `onboarding.country_selected` | `countryCode`, `source` (landing\|questionnaire) |
| `catalog.resolved` | `businessId`, `catalogCode`, `regionCode`, `via` (override\|region\|default) |
| `catalog.pack_adopted` | `catalogCode`, `packId`, `storeKitId`, `storeTypes[]`, `importedCount` |
| `onboarding.vertical_selected` | `storeTypes[]` |
| `business.region_changed` | SA old/new country·currency·timezone; WARN when risk acknowledged |

**Definition of done (Phase 5)**

- [x] Funnel events emitted on onboard, questionnaire country/vertical, catalog meta resolve, pack adopt
- [x] SA country/currency change blocked with 409 unless `acknowledgeRegionRisk=true` when products/sales exist; audited
- [x] SA business detail UI collects country/currency/timezone and confirms risk
- [x] SA catalog scope risk row updated (§19 / parent doc)

---

## 9. API / DTO deltas (sketch)

### Country → defaults map (new, canonical)

No such map exists today. Add one — **backend source of truth**, mirrored to FE for the picker. Seed from `frontend/setup/page.tsx` lists:

```text
KE → KES, Africa/Nairobi, VAT 16, catalog default,      dial +254, placeholders [Mirema,…]
UG → UGX, Africa/Kampala, VAT 18, catalog ug-retail,   dial +256, placeholders [Kampala,…]
TZ → TZS, Africa/Dar_es_Salaam, VAT 18, (no catalog → off)
RW → RWF, Africa/Kigali, VAT 18, (off)
NG → NGN, Africa/Lagos, VAT 7.5, (off)
ZA → ZAR, Africa/Johannesburg, VAT 15, (off)
```

VAT rates above are **defaults for new shops** (editable in settings). Confirm legal rates with product before ship; do not backfill existing tenants.

Only KE (and UG once green) are self-serve-enabled (§7).

**Note:** Desktop `CURRENCIES` includes `USD`/`EUR` with no country — cloud will not offer those; desktop override remains (§5.2).

### Cloud onboard

**Today:**

```text
POST /api/public/hosts/resolve/onboard   (path per PublicHostResolveController)
Body: { name, host }
→ always KE / KES / Africa/Nairobi
```

**Target:**

```text
Body: { name, host, countryCode? }
→ Business with derived currency + timezone (+ seed VAT default when that path exists)
→ 400 if countryCode not in self-serve-enabled set
→ omit countryCode → KE defaults (backward compatible)
```

Update FE `onboardBusiness(host, name, countryCode?)` and all callers.

### Update business (tenant)

```text
UpdateBusinessRequest += optional:
  currency?       // locked after onboarding completed/dismissed
  countryCode?
  timezone?
```

Null = unchanged. Validation: ISO length; country in allow-list for tenant self-serve; currency must match country map unless SA (or desktop override path).

### Onboarding answers (optional mirror)

Persist `countryCode` in `settings.onboarding.answers` for funnel analytics. **Source of truth** remains `businesses.country_code`.

### Store types

```text
StoreTypeCodes.PATTERN += cosmetics|wines-spirits
```

Keep pattern as the single backend source of truth; FE enums must match.

---

## 10. Catalog resolution (unchanged — document for implementers)

```text
1. business.settings.globalCatalogCode (published catalog by code)
2. business.country_code → global_catalogs.region_code (published)
3. Else catalog code = default
4. Else any published catalog
```

Phase 1 only needs to **set `country_code` correctly** and **gate countries without a regional catalog**; do not reinvent resolution.

Pack ranking remains a **presentation** concern on top of the resolved catalog.

---

## 11. Currency display — strategy + hit list (Phase 2)

### Fix the helper first, not the call sites

Highest-leverage fix: `formatMoney` in `frontend/lib/utils.ts`. Today:

```ts
new Intl.NumberFormat("en-KE", {
  style: "currency", currency,
  minimumFractionDigits: 2, maximumFractionDigits: 2,   // ← wrong for UGX/RWF
}).format(value)
```

Change to:

1. Map currency → locale (`KES→en-KE`, `UGX→en-UG`, `RWF→en-RW`, …; fallback `en`)
2. **Do not** force 2 fraction digits — let `Intl` use ISO defaults (UGX/RWF = 0)
3. Remove silent `currency = "KES"` default; prefer a business-currency provider/hook

### Then remove the silent KES default

`formatMoney(amount, currency = "KES")` means forgotten args silently print KES. Drop the default or route all money through a currency-aware provider seeded from `business.currency`.

### Scale (don’t undersell it)

~40+ frontend files reference `"KES"` / `en-KE` / `KSh`; several have 10+ occurrences. Sweep by surface, not a one-liner.

| Area | Pointer | Priority |
|------|---------|----------|
| FE helper | `frontend/lib/utils.ts` `formatMoney` | **P0 — do first** |
| POS / sales | `transactions-page`, `sales-overview-page`, cashier/grocery/butcher workspaces | P0 |
| Catalog / stock / supplies | `products/catalog`, `stock-levels-page`, supplies drawers | P1 |
| Credits / customers | credit activity, mark-paid, customers page | P1 |
| Shift cash | `KES_DENOMINATIONS` / `SalesConstants.KES_DENOMINATIONS` | P1 |
| Emails / PDFs (backend) | `OrderConfirmationEmailRenderer`, `RestockOrderPdfRenderer`, `sales-activity-pdf` | P1 |
| Customer storefront | `public-storefront.ts`, `storefront-shell`, coming-soon `KSh` | P2 |
| SEO | `platform-seo` `priceCurrency` | P2 |
| Mobile apps | `mobile/apps/{cashier,shopper,grocery,stock,admin}` | **Separate track** |
| Payments | Kopokopo / gateway currency defaults | Tied to §12 |
| Marketplace offers | Separate product — note defaults, don’t block | P3 |

---

## 12. Localization dependencies (payments, phone, tax)

Currency + catalog are necessary but **not sufficient** to operate in a new country.

| Concern | Kenya-baked today | Multi-country need | v1 stance |
|---------|-------------------|--------------------|-----------|
| **Mobile-money payments** | M-Pesa STK (`MpesaStkIntent`, `GatewayStkPushService`, Kopokopo) | Per-country rails (MTN MoMo UG, Airtel, …) | **Dependency.** Don’t enable self-serve without a payment story (§5.10) |
| **Phone number parsing** | `KenyanPhoneForms` assumes `+254` | Per-country dial code / validation | Follow-up; needed before SMS/STK in-country |
| **Receipt / SMS copy** | KES + Kenyan phrasing | Currency + locale aware | Rides Phase 2 + copy pass |
| **Tax / VAT default** | `DesktopSetupRequest.taxRate` default **16%** | Per-country default in region map | Seed on create; editable in settings; **no backfill** of existing shops |

**Locked default:** country off the picker until payments exist, unless product records a cash/credit-only exception in config.

---

## 13. Data integrity & migration

| Concern | Reality / stance |
|---------|------------------|
| Existing KE tenants | Already `KES`/`KE`/`Nairobi` — **no backfill**; behavior unchanged |
| Changing currency does **not** convert stored amounts | Prices, sales, credits are bare numbers. `KES→UGX` re-labels `1,200` → `UGX 1,200`. Hence the lock (§5.4) |
| Zero-decimal currencies | Storage stays decimal; **display** must not force `.00` (§11). Spot-check tax/discount rounding |
| Catalog currency vs business currency | Adopt copies numbers into tenant `items`. Mismatched catalog (KE fallback) ⇒ wrong-currency prices — readiness gate exists to prevent this |
| No new columns for Phase 1 | `currency` / `country_code` / `timezone` already exist |
| **In-flight questionnaires** | Existing `pending`/`active` states with `step` 1–6 and no `countryCode`: treat missing country as KE (current truth). If region becomes step 1, remap stored step `n → n+1` only when feature flag ships; prefer landing collection so step count stays 6 and no remap is needed |
| Desktop tenants with USD/EUR | Allowed; cloud self-serve will not create these without SA |

---

## 14. Out of scope

- FX rates / multi-currency ledgers / historical amount conversion
- Catalog-per-vertical
- Auto-translating KE product names for UG
- Per-country payment rails (M-Pesa alternatives) — **dependency, tracked in §12**
- Phone-number localization beyond dial-code defaults in the region map
- Mobile-app currency sweep (separate track; §11)
- Supplier Marketplace regionalization
- Changing branch-level currency (currency stays business-scoped)
- Filling every East African country on day one
- Changing `GlobalCatalogResolver` fallback semantics (prefer empty shells instead)

---

## 15. Test plan (minimum)

| Case | Layer |
|------|--------|
| Onboard with `countryCode=UG` → UGX + Africa/Kampala + resolve `ug-retail` | IT |
| Onboard omit country → KE defaults (backward compatible) | IT |
| Onboard with `countryCode=TZ` (not enabled) → 400 | IT |
| Tenant PATCH country during pending/active succeeds | IT |
| Tenant PATCH country after completed/dismissed → 403/400 | IT |
| SA PATCH country after completed succeeds | IT |
| Store type `cosmetics` accepted on profile + onboarding | IT / unit |
| Pack with `store_kit_id=cosmetics` ranks before unrelated packs | FE unit / manual |
| `formatMoney(1500, "UGX")` → no `.00` | FE unit |
| `formatMoney(1500, "KES")` → 2dp preserved | FE unit |
| Country with no regional catalog cannot be set via self-serve | IT |
| Empty `ug-retail` → stock-shelves empty state, not KE browse | FE / IT |
| Region map BE ↔ FE stay in sync for KE/UG defaults | unit |

---

## 16. Build order

```text
1. Phase 1 region wire-up     → stop lying about KE on cloud create
2. Phase 2 money display      → UGX (etc.) readable in product
3. Phase 3 new verticals      → cosmetics / wines packs on KE
4. Phase 4 fill UG + enable   → self-serve multi-country real
5. Phase 5 telemetry / lock   → ops + metrics
```

Do not enable UG in the public picker before Phase 4 content **and** payment story (unless product records an exception).

**Parallel tracks (don’t block Phase 1):** payment-rail research for UG; mobile money-format audit.

---

## 17. Effort sketch

| Phase | Scope | Notes |
|-------|--------|--------|
| 1 | Onboard + PATCH + all signup callers + readiness reject | Small backend; FE across 4+ surfaces; highest leverage |
| 2 | `formatMoney` + critical call sites | Wide but mechanical; slice by surface |
| 3 | Store types + kits + SA pack content | Code small; **content** is the cost |
| 4 | UG catalog population + readiness flip | Ops / promote / clone — largest content effort |
| 5 | Telemetry + SA warnings | Thin |

§12 payment rails and mobile-app money sweep are **separate efforts** not counted above.

---

## 18. Key file pointers

| Area | Path |
|------|------|
| This scope | `backend/docs/ONBOARDING_REGION_AND_CATALOG_SCOPE.md` |
| SA global catalog | `backend/docs/SUPER_ADMIN_GLOBAL_CATALOG_SCOPE.md` |
| Parent catalog plan | `backend/docs/GLOBAL_PRODUCTS_CATALOG_PLAN.md` |
| Cloud hardcoded create | `.../tenancy/application/PublicHostResolverService.java` |
| Cloud onboard API | `.../tenancy/api/PublicHostResolveController.java` |
| Catalog resolver | `.../globalcatalog/application/GlobalCatalogResolver.java` |
| Update DTO | `.../tenancy/api/dto/UpdateBusinessRequest.java` |
| Onboarding answers | `.../tenancy/api/dto/OnboardingAnswersDto.java` |
| Onboarding status | `.../tenancy/api/dto/OnboardingPatchRequest.java` |
| Store types | `.../tenancy/api/dto/StoreTypeCodes.java` |
| Desktop setup | `.../desktop/application/DesktopSetupService.java`, `DesktopSetupRequest.java` |
| Country/currency/tz lists | `frontend/setup/page.tsx` |
| Questionnaire | `frontend/lib/onboarding-questionnaire.ts` |
| Apply side effects | `frontend/lib/onboarding-questionnaire-apply.ts` |
| FE onboard helper | `frontend/lib/api.ts` (`onboardBusiness`) |
| Onboard callers | `landing-signup-modal.tsx`, `(auth)/signup/**`, `(auth)/login/staff/page.tsx` |
| Tenant catalog UI | `frontend/app/(dashboard)/products/catalog/page.tsx` |
| Money helper | `frontend/lib/utils.ts` (`formatMoney`) |
| Payment rails (KE) | `MpesaStkIntent`, `GatewayStkPushService`, `KenyanPhoneForms` |
| UG empty catalog seed | `V159__global_catalog_seed_ug_empty.sql` |
| Mobile money surfaces | `mobile/apps/{cashier,shopper,grocery,stock,admin}` |

---

## 19. Parent doc sync

| Said previously | Now |
|-----------------|-----|
| Risk: only KE catalog / `country_code` mitigation not real | **Updated:** self-serve KE+UG; UG catalog populated (margin-only); resolver via override/region/default; SA region change acknowledgement + audit |
| Job to be done: Kenya retail only | Still true for **content density / imaged packs**; region wiring is multi-country |

Keep Marketplace and Global Catalog terminology separate.

---

## 20. Changelog

| Version | What changed |
|---------|----------------|
| **v3** | Locked §5 decisions; TOC + ship/wait; all onboard entry points; readiness bar + config home; empty-catalog UX; API contracts; in-flight questionnaire migration; desktop derive-defaults rule; telemetry event sketch; corrected SA parent sync (UG shell already exists) |
| **v2** | Money-format correctness; payments/locale; data integrity; mobile surfaces |
| **v1** | Initial region × vertical onboarding scope |
