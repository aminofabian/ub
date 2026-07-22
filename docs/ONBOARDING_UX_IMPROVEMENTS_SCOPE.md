# Onboarding UX Improvements — Scope

**Status:** Phase A + B quick wins in progress (FE)  
**Date:** 2026-07-22  
**Depends on:** `ONBOARDING_REGION_AND_CATALOG_SCOPE.md`, `SUPER_ADMIN_GLOBAL_CATALOG_SCOPE.md`, global catalog adopt UI  
**Related:** questionnaire (steps 1–6), products catalog review/import, post-setup checklist  
**Owners (proposed):** Product (activation gating), Frontend (funnel + catalog happy path), Ops/SA (pack images / regional content)

> **Goal:** Make first-run feel like one continuous path from “create shop” → “can sell soon,” without redesigning the solid configure-the-shop steps (1–5).

---

## 1. Current flow (code truth)

```text
Signup (pick country) → create business (onboarding = pending)
        ↓
Fullscreen questionnaire
  1 Locations          (branch count + localities)
  2 Store type(s)      (verticals)
  3 Departments        (= item types / section kits)
  4 Sell online?
  5 Branding           → “Finish setup” applies entities
                       → status = completed
  6 Stock your shelves → CTA only (optional epilogue)
        ↓
Catalog (?from=onboarding)  OR  Products  OR  Hub
        ↓
Post-setup checklist (48h): sale / staff / reports
  ← no stock / catalog CTA
```

### What works

- Country at create → currency / timezone / catalog resolution
- Clear 6-step progress, server-backed answers, resume after abandon
- Store-type → department kits → pack ranking on catalog
- Empty-catalog honesty when regional shell has no SKUs
- Catalog review/import dialog + import progress % (recent)

### Entry points

| Entry | Notes |
|-------|--------|
| Landing signup modal | Country + name → onboard → register |
| `/signup`, `/signup/staff`, `/login/staff` | Cloud create paths |
| `/verify-email` | Marks questionnaire pending |
| Dashboard layout | Hydrates GET onboarding; opens overlay if `pending`/`active` |
| Desktop `/setup` | Seeds pending (parity rule only for this scope) |

**Not a hard app lock:** questionnaire is a fullscreen overlay; **Skip for now** → `dismissed`.

---

## 2. Diagnosis — friction

| Issue | Why it hurts | Evidence |
|-------|--------------|----------|
| **Stock is post-“done”** | Finish at branding marks `completed`; step 6 is optional. Easy to leave with an empty catalog. | `onboarding-questionnaire-provider.tsx` completes at step 5, then advances to 6 |
| **Post-setup ignores inventory** | Hub pushes sale / staff / reports before stock exists. | `post-setup-checklist.tsx` |
| **Catalog feels bolted on** | `?from=onboarding` toast + auto-pack only — not a guided “import this pack → you’re live” path. | `products/catalog/page.tsx` |
| **“Departments” ≠ categories** | Step 3 creates item types; categories appear later in import. Taxonomy confusion. | `onboarding-questionnaire-apply.ts` (`createItemType` only) |
| **Dead guided tour still around** | Not mounted, but `?onboarding=` hooks + `markOnboardingTourPending` remain. Dual mental model. | Tour provider unmounted from layout; tour steps still in `onboarding-tour.ts` |
| **Skip wipes answers** | Harsh if dismissed by accident. | `dismissOnboardingQuestionnaire` |
| **Marketing still CSV-flavored** | Landing copy lags catalog-first story. | `landing-how-it-works.tsx` |

### Two jobs onboarding must do

| Job | Steps | Status |
|-----|-------|--------|
| **A. Configure the shop** | 1–5 | Mostly solid |
| **B. Get sellable stock** | 6 + catalog + hub follow-up | Redesigned but loosely attached |

Highest leverage = tighten **B** into the completion / activation story.

---

## 2b. Screen-by-screen audit (code truth, per step)

Source: `onboarding-questionnaire.tsx` + provider. Aligned with the locked decisions in
`ONBOARDING_REGION_AND_CATALOG_SCOPE.md` §5–§6 (country on create, 6 steps, empty-catalog honesty).

### Step 1 — Your shop locations

**Today:** branch count (1–5+) then a required name input per branch; live “→ {Name} branch” preview; country-aware locality placeholders (KE/UG/TZ/RW/NG/ZA).

| Issue | Improvement | Effort |
|-------|-------------|--------|
| All branch names required before Continue — a 5-branch owner types 5 localities on screen 1 | Require **only branch 1**; blank slots auto-name (“Branch 2”) and are editable later in Branches. Copy: “You can rename these anytime.” | S |
| “5 or more” silently caps at 5 | Keep cap but say it **before** the inputs, not after | S |
| No context for why we ask | One-liner: “Each branch gets its own stock and sales.” | S |

### Step 2 — What kind of shop

**Today:** multi-select store types incl. cosmetics / wines-spirits (Phase 3 verticals shipped); hint text per option.

| Issue | Improvement | Effort |
|-------|-------------|--------|
| Changing types after step 3 silently wipes department picks | Keep wipe (correct) but toast/inline note: “Departments reset to match your new shop type.” | S |
| No signal that this drives catalog packs later | Hint: “We use this to suggest starter products at the end.” Sets up step 6. | S |

### Step 3 — Choose your departments

**Today:** auto-selects all suggested kits; chips + custom add; Select all / Clear; skippable.

| Issue | Improvement | Effort |
|-------|-------------|--------|
| “Departments” creates item types, not POS categories — taxonomy confusion later (B1) | Rename to **“Product sections”** + subtitle “How items are grouped at the till and in reports.” | S |
| Auto-select-all makes Continue a rubber stamp; users don’t realize these become real entities | Keep auto-select (good default) but add “These become your product sections — you can edit them anytime.” | S |

### Step 4 — Sell online?

**Today:** bare yes/no; no consequence explanation.

| Issue | Improvement | Effort |
|-------|-------------|--------|
| User can’t tell what “yes” does | Show outcome under Yes: “Get a web shop at **{slug}.palmart…** — customers browse and order online.” Under No: “You can turn this on later in Settings.” | S |

### Step 5 — Brand your shop (the heavy step)

**Today:** display name (suggested), color presets + contrast rule, preview modal, optional logo (≤4 MB); **Finish setup** applies all entities (branches, item types, storefront, branding, logo) then marks `completed`.

| Issue | Improvement | Effort |
|-------|-------------|--------|
| One button does five writes; failure gives one generic error and re-runs everything | Make `applyOnboardingQuestionnaire` steps idempotent/resumable; on failure, tell the user **what** failed (“Branches created; logo upload failed — retry or skip logo”). | M |
| “Finish setup” label + 83% progress implies more to come, but status flips to `completed` here | Rename button to **“Create my shop”**; progress copy at step 6 becomes “Last step: stock your shelves.” | S |
| Contrast error appears only after picking bad colours | Fine as-is (presets are contrast-safe); no change. | — |

### Step 6 — Stock your shelves (the weak link)

**Today:** text CTA card. Browse catalog / add manually / finish later. Empty-shell variant honest (per §5.12). No pack preview, no import from here, completion already happened.

| Issue | Improvement | Effort |
|-------|-------------|--------|
| Zero product evidence — user must take it on faith and context-switch to catalog | **Show the suggested pack inline**: pack name, product count, a few product names/images, currency-correct prices. Primary CTA: “Import {pack} → review”. | M |
| “Browse product catalog” is a detour, not a path | Keep as secondary CTA. Primary = suggested pack (matches §6 “vertical pack for you”). | S |
| “I’ll add products later” has no follow-up | Pairs with hub banner / checklist (Phase A2–A3). | S |

### Cross-cutting

| Issue | Improvement | Effort |
|-------|-------------|--------|
| Progress % (`step/6`) counts the post-complete step | Show 5 answer steps as the bar; step 6 renders as “Final step” state, not 100%-plus | S |
| **Skip for now** on every step wipes answers (D5) | Keep answers on dismiss; hub “Resume setup” chip while `dismissed` + shop empty | S |
| Country/currency never confirmed inside questionnaire | Read-only chip in the header (e.g. “🇰🇪 Kenya · KES”) — reassures the §6 “where you operate” promise without adding a step | S |
| No funnel telemetry (region doc Phase 5) | Emit step-view / finish / pack-adopt events when instrumenting Phase A | M |

---

## 3. Decisions (lock before build)

| # | Decision | Options | Recommendation |
|---|----------|---------|----------------|
| D1 | When is the shop “done”? | **A1** Complete at branding; strengthen step 6 + hub. **A2** Gate “ready” until stocked or explicit skip-with-intent. | **A1** (smaller change, still high impact) |
| D2 | Onboarding pack import | **One-tap** import pack defaults (no review). **Slim review** (defaults pre-filled, conflicts bulk-resolved). | **Slim review** (keeps price control, still fast) |
| D3 | Butchery-only finish | Same catalog path vs different stock story | Same catalog path + butchery pack ranking |
| D4 | Legacy tour | Delete deep-links vs leave as hidden power-user | **Delete / unwire** (retire dual model) |
| D5 | Skip behavior | Clear answers (today) vs keep answers + Resume | **Keep answers** + Resume setup on hub |

---

## 4. Phased scope

### Phase A — Activation (highest leverage)

**Job:** Treat “shop can sell something” as the real finish line *in the UX*, without necessarily changing server `completed` semantics (per D1=A1).

| Work item | Detail | Effort |
|-----------|--------|--------|
| **A0. Step 6 shows the goods** | Fetch suggested pack (store-type match) on step 6 and render it inline: pack name, product count, sample products, currency-correct prices. Primary CTA “Import {pack}” deep-links to catalog with the pack pre-selected. | M |
| **A1. Onboarding-aware catalog happy path** | From step 6 / `?from=onboarding`: pack pre-selected → slim review (defaults on, `createMissingCategories` default on for this path) → progress % → success → hub with “X products ready.” | M |
| **A2. Post-setup: stock first** | Checklist order: Import starter pack → First sale → Invite staff → Reports. Mark stock item done when tenant has ≥1 product (or pack adopted). | S |
| **A3. Soft banner for skippers / empty catalogs** | Hub + Products: persistent “Stock your shelves” until first import or explicit dismiss. | S |

**Success criteria**

- From step 6, a KE mini-mart can import a starter pack in **&lt; 3 clicks** after landing on catalog (excluding review field tweaks).
- Post-setup shows stock CTA before “first sale” when catalog is empty.
- Users who skip step 6 still see a recoverable stock prompt on hub/products.

**Out of Phase A:** changing server status machine to require stock (A2 gating); pack image ops; UG content fill.

---

### Phase B — Clarity & cleanup

| Work item | Detail | Effort |
|-----------|--------|--------|
| **B1. Departments copy** | Rename / explain step 3: e.g. “Product sections” — “how you group items at the till.” Avoid implying POS categories. | S |
| **B2. Onboarding import defaults** | For `?from=onboarding` (and step-6 entry), default `createMissingCategories` on. | S |
| **B3. Retire legacy tour** | Unwire `?onboarding=` drawer openers; rename `markOnboardingTourPending` → questionnaire pending; remove or quarantine dead tour UI. | S |
| **B4. Skip preserves answers** | `dismissed` keeps answers; hub “Resume setup” re-opens overlay. | S |
| **B5. Landing copy** | Align “how it works” / stock messaging with catalog packs (not CSV-first). | S |
| **B6. Per-step micro-copy** | Screen-by-screen fixes from §2b: step-1 only branch 1 required, step-4 consequence copy, step-5 button rename (“Create my shop”), step-2 “resets departments” note, progress bar counts 5 answer steps. | S–M |
| **B7. Country chip in header** | Read-only “{flag} {country} · {currency}” chip on the questionnaire card. | S |
| **B8. Resumable apply** | Split `applyOnboardingQuestionnaire` failure reporting (branches vs logo vs storefront); don’t re-run succeeded writes on retry. | M |

**Success criteria**

- No live path depends on guided-tour deep links for first-run.
- Skip → Resume restores prior step/answers.
- New users are not told CSV is the primary stock path.

---

### Phase C — Content / region (ops + SA; not FE-only)

Ceiling on “intuitive” when catalogs are thin. Already called out in parent docs:

- Pack images + dense vertical packs (KE)
- Honest empty shells for early countries (UG)
- Self-serve country readiness gates (catalog + payments)
- Remaining currency / payment localization

FE cannot paper over empty regional catalogs.

---

## 5. Explicitly out of scope (this pass)

- Full payment localization (MTN MoMo, etc.)
- Multi-currency / FX ledgers
- Redesigning questionnaire steps 1–5 structure
- Mobile onboarding parity
- Desktop `/setup` full rewrite (keep parity: same pending seed + country rules)
- Super Admin catalog curation UI (separate SA scope)

---

## 6. Build order

```text
1. Catalog happy path from onboarding (pack → slim review → success)
2. Post-setup checklist: stock first + empty-catalog banner
3. Copy / taxonomy + skip-preserve + tour cleanup
4. Landing copy alignment
5. (Parallel) Ops: packs / images / region readiness
```

---

## 7. State machine (unchanged vs optional)

### Today (keep under D1=A1)

```text
idle → pending → active → completed   (at branding / Finish setup)
                 ↘ dismissed          (Skip for now)
Step 6 = post-complete epilogue
```

### Optional later (D1=A2 — not default)

```text
… → setup_completed (branding)
  → stocked | stock_skipped
Hub “ready” / checklist completion waits for stocked | stock_skipped
```

Do **not** implement A2 unless Product explicitly locks D1=A2.

---

## 8. Key file pointers

### Questionnaire

- `frontend/lib/onboarding-questionnaire.ts`
- `frontend/lib/onboarding-questionnaire-apply.ts`
- `frontend/components/onboarding/onboarding-questionnaire.tsx`
- `frontend/components/onboarding/onboarding-questionnaire-provider.tsx`
- `frontend/components/onboarding/selfserve-country-select.tsx`

### Catalog / import

- `frontend/app/(dashboard)/products/catalog/page.tsx`
- `frontend/components/products/global-catalog-build-paths.tsx`
- `frontend/components/products/global-catalog-review-import-dialog.tsx`
- `frontend/components/products/global-catalog-import-progress.tsx`

### Hub / leftover tour

- `frontend/components/business-hub/post-setup-checklist.tsx`
- `frontend/lib/onboarding-tour.ts`
- `frontend/components/onboarding/onboarding-tour-provider.tsx` (unmounted)

### Docs / backend

- `backend/docs/ONBOARDING_REGION_AND_CATALOG_SCOPE.md`
- `backend/docs/SUPER_ADMIN_GLOBAL_CATALOG_SCOPE.md`
- `backend/.../BusinessOnboardingSettingsService.java`
- `backend/.../MyOnboardingController.java`

---

## 9. Test plan (minimum)

| Case | Expect |
|------|--------|
| New KE mini-mart → step 6 → Import starter pack | Pack suggested; slim review; progress %; hub shows products ready |
| Empty regional catalog (shell) | Step 6 empty UX; no dead “browse catalog” primary; manual add path |
| Skip at step 3 | `dismissed`; answers retained; Resume restores step 3 |
| Completed, 0 products | Hub checklist leads with stock; banner on products |
| After first adopt | Stock checklist item complete; banner gone |
| Legacy `?onboarding=categories` | No broken drawer tour (or harmless no-op after retire) |
| Butchery-only | Finish still reaches catalog/pack path without dead ends |

---

## 10. Effort sketch

| Phase | FE effort | Notes |
|-------|-----------|--------|
| A | ~1–2 days | Happy path + checklist + banner |
| B | ~0.5–1 day | Copy, skip, tour retire, landing |
| C | Ops/SA ongoing | Not FE-only |

---

## 11. Open questions

1. Confirm **D1=A1** (complete at branding) vs A2 (gate ready on stock).
2. Confirm **slim review** vs true one-tap import for onboarding packs.
3. Butchery-only: any non-catalog stock story required in Phase A?
4. Tour: hard-delete vs feature-flag quarantine.
5. Should “first pack adopted” emit funnel telemetry (country → vertical → pack) in Phase A or wait?

---

## 12. Changelog

| Date | Change |
|------|--------|
| 2026-07-22 | Initial scope from live questionnaire + catalog + hub audit |
| 2026-07-22 | v2 — added §2b screen-by-screen audit (steps 1–6 + cross-cutting); Phase A gains A0 (inline pack preview on step 6); Phase B gains B6–B8 (micro-copy, country chip, resumable apply) |
| 2026-07-22 | v3 — started implementation: A0–A3 + B quick wins (step-6 pack card, catalog `packId` happy path, hub stock banner/checklist, skip-preserve + resume, departments→sections, Create my shop) |
