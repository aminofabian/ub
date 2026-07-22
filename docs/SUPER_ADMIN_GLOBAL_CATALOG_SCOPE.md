# Super Admin Global Catalog — Scope

### Platform curation for the shared product library (images, promote from Palmart, publish).

**Status:** Scoped (v4 — Phase 5 regional catalogs)  
**Depends on:** Phase A tenant browse/adopt (shipped), existing Super Admin shell  
**Related:** `GLOBAL_PRODUCTS_CATALOG_PLAN.md`, `MULTI_TENANT_RESOLUTION.md`, `SUPPLIER_MARKETPLACE_SCOPE.md`

---

## 1. Why this exists (business)

### Problem

New shops start with departments/types but **zero products**. Manual entry is slow. The global library exists (~2.4k Kenya retail SKUs) but is incomplete for real onboarding:

- No portable images (`image_url` is NULL — seed stripped tenant `/api/media` paths)
- Some rows are poorly fed vs live Palmart (names, barcodes, prices, categories)
- Ops cannot fix or enrich templates without Flyway / offline CSV scripts
- Adopt does not create a proper tenant image gallery even when a URL exists

### Job to be done

> Platform ops keep a curated Kenya retail template library accurate and image-rich so a new shop can stock shelves from catalog in under 10 minutes during onboarding — without sharing Palmart’s live inventory.

### Who

| Role | Job |
|------|-----|
| **Platform / Super Admin ops** | Curate, image, publish, promote from Palmart, manage packs |
| **Shop owner / manager** | Browse + adopt only (`/products/catalog`, onboarding “Stock your shelves”) |
| **Not involved** | Marketplace suppliers (different product — see §3) |

### Success metrics

Each needs a baseline + telemetry, or it is a slogan, not a metric. Instrument on ship.

| Metric | Target (MVP+) | How measured |
|--------|----------------|--------------|
| **Pack SKUs** with HTTPS `image_url` | ≥ 90% of the 3 starter packs | SA meta counts by pack; this is the real MVP DoD (see §7.0) |
| All published products with HTTPS `image_url` | Rising (secondary) | SA `missingImage` count trend |
| Adopt → visible cover on tenant product | 100% when global has image | adopt-result event: `imageRegistered` per line |
| Time for new shop to adopt first starter pack | &lt; 10 minutes | onboarding funnel timestamp; guard against re-host latency (§7.1, §11) |
| Ops time to fix a bad template | Minutes in SA UI, not a migration | qualitative |
| Duplicate global rows on same barcode after promote | 0 | **Only guaranteed with the DB dedup index (§7.1 barcode policy); app-only checks race → downgrade to "reconciled to 0" if index deferred** |
| Starter-pack adoption → tenant activation/retention | Define baseline | tie catalog to activation so the build cost is justified |

### Business risks

| Risk | Mitigation |
|------|------------|
| Promote “any business” leaks private assortment into platform | Default Palmart; confirm source; promote → **draft**; review before publish |
| **Re-hosting Palmart product photos into a library shared with competing shops** | IP/licensing exposure. Prefer generic barcode/pack-shot images; confirm rights before promoting branded lifestyle shots |
| KES / Kenya-shaped prices mislead other countries | **Mitigated for self-serve:** UG resolves to `ug-retail` (cloned with absolute prices scrubbed). Prefer `suggestedMarginPct` over absolute sell at adopt. SA country/currency change requires `acknowledgeRegionRisk` when products/sales exist (amounts re-label, not convert). |
| Flyway reseed after shops have adopted | **Forbidden** once promote era starts — wipes provenance / breaks links |
| Stale recommended prices erode trust | Draft review + version bump; periodic price refresh (Phase 3) |
| Confusing Marketplace with Global Catalog | Separate SA nav + this doc’s terminology (§3) |

---

## 2. Goal (architecture)

Make **Super Admin** the system of record for platform templates. Tenants **copy-on-adopt** into their own `items`. Stock stays branch-local.

| Layer | Ownership | Mutable by tenant? |
|-------|-----------|-------------------|
| `global_products` (+ categories, packs) | Platform (Super Admin) | No |
| Tenant `items` after adopt | Per `business_id` | Yes — independent copy |

Provenance: `items.global_product_source_id` (link for “already imported,” not a live FK join).

```text
Palmart (flagship tenant) ──promote / re-host images──► global_* (platform)
                                                          │
                                              adopt (copy)│
                                ┌─────────────────────────┼──────────────────┐
                                ▼                         ▼                  ▼
                          Business A items          Business B …        Business C …
                          + local stock             + local stock       + local stock
```

---

## 3. Marketplace ≠ Global Catalog

| | **Global Catalog** | **Supplier Marketplace** |
|--|--------------------|---------------------------|
| Purpose | Product **templates** for new shops | B2B vendors / connect / POs |
| SA surface | `/super-admin/platform/global-catalog` (this scope) | Marketplace suppliers APIs (separate) |
| Tenant outcome | Rows in `items` | Supplier links & ordering |
| Stock | Never shared | N/A for templates |

Do not put Global Catalog under Marketplace nav or reuse marketplace promote language.

---

## 4. Current state (code truth)

### Shipped (Phase A — tenant)

| Area | Status |
|------|--------|
| Browse + adopt UI | `/products/catalog` + onboarding empty-state CTAs |
| APIs | `GET/POST /api/v1/global-catalog/{meta,products,lookup,packs,adopt…}` |
| Seed | ~2.4k products via V122/V123 — **`image_url` all NULL** |
| Region resolution | `business.country_code` → catalog `region_code`, else `default` |
| Match / already-imported | `TenantCatalogMatchIndex` (barcode, SKU, name+brand+size, provenance) |
| Packs | `mini-mart-starter`, `beverages-pack`, `grocery-basics` (`store_kit_id` linked via V157 + SA Packs editor) |
| Permissions (tenant) | `catalog.global.read` / `.adopt` granted to owner/admin/manager/stock_manager |

### Not built (this scope)

| Area | Status |
|------|--------|
| Super Admin Global Catalog UI/API | Missing |
| Portable images + SA upload to `global-catalog/` | Missing |
| Adopt → `item_images` registration | Missing (only optional `items.image_key`) |
| Promote tenant → global | Missing (offline CSV → SQL only) |
| **Status lifecycle (`draft` / `archived`)** | **Net-new.** No enum exists; code only ever compares to literal `"published"`. Tenant reads already hide non-published, but there is no way to *set* draft/archived — the entire write side is greenfield |
| **Optimistic locking on categories / packs** | Only `global_products` + `global_catalogs` have a `version` column. `global_categories` / `global_product_packs` do **not** → edits are last-write-wins |
| `platform.global_catalog.*` | Seeded in V121; **ungranted, unused** — SA uses `ROLE_SUPER_ADMIN` |

### Seed / image root cause

- Generator: `backend/scripts/generate_global_catalog_seed.py`
- Source: Palmart CSVs under `frontend/public/imports/`
- `normalize_global_image_url()` **drops** `/api/media/...`; keeps only absolute `http(s)`
- Palmart export had local media paths, not CDN URLs → all NULL in global seed
- **Committed V122/V123 still contained 41 `/api/media/` rows; `V124__global_catalog_clear_tenant_image_urls.sql` now NULLs them at migration time.** So "all NULL" is true today — the root cause is *closed*, and the remaining problem is purely "no images yet," not "wrong images"

### Cover vs gallery (important)

Tenant cover can render from HTTPS `items.image_key` alone (`coverImageUrl`). Gallery / multi-image UX expects `item_images`.

**Phase 1 adopt fix must do both:** set cover **and** register `item_images` (Cloudinary `secure_url` + `public_id`, or re-upload into tenant folder). Bare URL without `public_id` is not enough for `registerItemImage` as implemented today.

> ⚠️ **Do NOT register the global `public_id` directly on a tenant `item_images` row.** Tenant image *delete* calls `CloudinaryImageService.destroyImage(public_id)`. If the tenant row carries the shared `global-catalog/` `public_id`, one tenant deleting its image **destroys the shared asset for every other tenant.** This is why the "acceptable v1: register external secure URL with public_id" shortcut is unsafe and Decision #6 (re-host into the tenant folder) is mandatory, not just "cleanest."

### Super Admin patterns to mirror

- Routes: `/super-admin/platform/integrations`, `/super-admin/payments/platform`
- API prefix: **`/api/v1/super-admin/...`** (not parent plan’s `/api/v1/platform/...`)
- Auth: path security `ROLE_SUPER_ADMIN` + SA JWT (**no** `business_id` on principal)
- Nav: `frontend/components/super-admin/super-admin-shell.tsx` → Platform group
- Client: `frontend/lib/super-admin-api.ts`

---

## 5. Non-goals

- Cross-tenant shared inventory or live stock sync
- Tenants editing / writing the global catalog
- Real-time “refresh from template” into already-adopted items (later)
- Full regional catalog productization beyond existing `country_code` resolution
- Variant groups / package SKUs in global templates (flat SKUs for now)
- Hard-delete of tenant sales history via “replace catalogue” (separate feature)
- **Flyway reseed of global products after shops have adopted**
- Merging Global Catalog into Supplier Marketplace

---

## 6. Image architecture

```text
[SA upload / promote re-host]
        │
        ▼
Cloudinary folder: global-catalog/
        │
        ▼
global_products.image_url = HTTPS delivery URL
(+ store public_id if we add a column later; v1 can parse from URL or re-upload on adopt)

        │  tenant adopt
        ▼
items.image_key = cover URL
item_images row = CLOUDINARY (secure_url + public_id)
  preferred: re-upload or copy into ub/{businessId}/items/...
  acceptable v1: register external secure URL if API allows with public_id
```

**Rules**

1. Never store tenant `/api/media/...` on `global_products.image_url`
2. Promote always **re-hosts** into `global-catalog/`
3. SA image upload uses a **dedicated SA endpoint** (server-side `CloudinaryImageService.uploadImageToFolder(..., "global-catalog/"+id, true)` — arbitrary folders are already supported), not the tenant signature folder `ub/{businessId}/items/...`
4. Tenant browse already filters `/api/media/` thumbs — HTTPS CDN URLs are required for SA + tenant previews
5. **Adopt re-hosts by remote-URL fetch, not byte streaming.** Cloudinary's upload API accepts a remote URL as the `file` field. Add an overload to `uploadImageToFolder` that passes the global `secure_url` straight into `ub/{businessId}/items/{id}` — no download-to-app-server round trip. Tenant then owns its own `public_id` (safe to delete per the §cover warning)
6. **Never `destroyImage` on unpublish/archive** (keeps the CDN URL); destroy only on an explicit "clear image" action (see §12)

> **Effort correction (vs §16):** uploading to `global-catalog/` is *trivial* — the folder API already exists. The hard parts are (a) adopt re-host + tenant-owned `public_id`, (b) doing it without blocking the adopt transaction (§7.1), and (c) the fold-in of the existing client-side folder bug: browser upload uses `ub/items/{itemId}` while server-side uses `ub/{businessId}/items/{itemId}`. Fix the mismatch while touching this code.

---

## 7. Phase 1 — Super Admin curation + images (MVP)

**Outcome:** Ops can fix bad rows, upload images, publish/unpublish, edit packs; tenant adopts land with visible cover + gallery row.

### 7.0 Scope discipline — do the activation-critical slice first

The business promise is **"images on the starter packs so a shop stocks shelves in <10 min."** That is gated by pack-SKU images, not by field-level editing.

- **Ship first (activation-critical):** adopt image-fix → image upload → `missingImage` triage → publish/unpublish toggle. Target: ≥90% of the 3 packs imaged.
- **Defer within Phase 1:** full field CRUD (names, brands, prices, stock hints). Data is "mostly OK," and **promote-from-Palmart (Phase 2) overwrites these fields at scale anyway** — hand-editing 2.4k rows in a form is low ROI.
- Manual upload is **triage-only**; the real scale image source is promote. Size the manual UI accordingly (don't gold-plate it).

> First, count SKUs in `mini-mart-starter` + `beverages-pack` + `grocery-basics`. **That number is the Phase 1 imaging backlog and the concrete MVP definition of done.**

### 7.1 Backend

| Deliverable | Notes |
|-------------|--------|
| `SuperAdminGlobalCatalogController` | `/api/v1/super-admin/global-catalog/...` |
| Products list | Pagination; filters: `q`, `status`, `categoryId`, `missingImage` |
| Products write | `POST` / `PATCH` — see field list below; honor `@Version` on `global_products` (it has a version column) |
| Status lifecycle | **Net-new** — introduce a `{draft, published, archived}` constant/enum + transition endpoint; no lifecycle exists in code today |
| Soft archive | Prefer `status=archived` over hard-delete |
| Categories (light) | Tree via `parent_id`; slug; `tenant_category_slug_hint`. **No `version` column → last-write-wins; either add one or accept it** |
| Packs (light) | Membership for existing three packs; optional `store_kit_id` later. Also no `version` column |
| Image upload | `POST .../products/{id}/image` multipart → Cloudinary `global-catalog/` |
| **Clear image** | `DELETE .../products/{id}/image` → NULL `image_url` (explicit; never on unpublish) |
| **Bulk publish** | `POST .../products/publish` with `ids[]` — needed for pack-quality workflow + promote publish |
| Auth | `ROLE_SUPER_ADMIN` (same as payments/integrations) |
| Adopt fix | If `image_url` present → cover + **`registerItemImage`** (chosen strategy in §6). **Run the re-host after item commit, non-fatally** — see box below |

> **Adopt transaction safety (protects the <10-min metric).** `GlobalCatalogAdoptLineExecutor.importLine` runs `REQUIRES_NEW` per line. Re-hosting an image is a network call; doing it *inside* the per-line transaction serializes dozens of uploads when adopting a full pack and risks timeouts. Register the image **after** the item commits; on failure the item still exists and the warning surfaces on `AdoptResultLineResponse.message` (that field already exists). Apply the same fix to the `onSkuConflict: merge` path (§11).

**Product fields in CRUD (align with `GlobalProduct`)**

Required / primary: `name`, `status`, `unitType`, `sellable`, `stocked`, `weighed`  
Identity: `barcode`, `skuTemplate`, `brand`, `size`  
Merchandising: `description`, `globalCategoryId`, `itemTypeKeyHint`, `sortOrder`  
Pricing hints: `recommendedBuyingPrice`, `recommendedSellingPrice`, `suggestedMarginPct`  
Stock hints: `defaultMinStockLevel`, `defaultReorderLevel`, `defaultReorderQty`  
Expiry: `hasExpiry`, `expiresAfterDays`  
Media: `imageUrl` (set via upload endpoint)

**Suggested endpoints**

```text
GET    /api/v1/super-admin/global-catalog/meta
GET    /api/v1/super-admin/global-catalog/products?q&status&categoryId&missingImage&page&size
GET    /api/v1/super-admin/global-catalog/products/{id}
POST   /api/v1/super-admin/global-catalog/products
PATCH  /api/v1/super-admin/global-catalog/products/{id}
POST   /api/v1/super-admin/global-catalog/products/publish     # bulk {ids[]}
POST   /api/v1/super-admin/global-catalog/products/{id}/image
DELETE /api/v1/super-admin/global-catalog/products/{id}/image  # explicit clear only
GET    /api/v1/super-admin/global-catalog/categories
POST   /api/v1/super-admin/global-catalog/categories
PATCH  /api/v1/super-admin/global-catalog/categories/{id}
GET    /api/v1/super-admin/global-catalog/packs
GET    /api/v1/super-admin/global-catalog/packs/{id}
PATCH  /api/v1/super-admin/global-catalog/packs/{id}
```

**Barcode policy — app checks alone cannot guarantee the "0 duplicates" metric.** Schema has barcode **index only**, not UNIQUE. Under concurrent promote, an app-level "does this barcode exist?" check races and can create two rows. To actually enforce it at the DB, add a generated column and unique index that exempts archived rows (MySQL treats NULLs as non-colliding):

```sql
ALTER TABLE global_products
  ADD COLUMN dedup_barcode VARCHAR(191)
    GENERATED ALWAYS AS (CASE WHEN status = 'archived' THEN NULL ELSE barcode END) STORED,
  ADD UNIQUE KEY uq_global_products_dedup_barcode (catalog_id, dedup_barcode);
```

(Use a normalized barcode if promote normalizes; keep it consistent with `TenantCatalogMatchIndex`.) If this index is deferred, downgrade the §1 metric from "0" to "reconciled to 0." Phase 1 list still warns on duplicates for ops triage.

### 7.2 Frontend

| Deliverable | Notes |
|-------------|--------|
| Route | `/super-admin/platform/global-catalog` |
| Nav | Platform → **Global Catalog** (sibling of Payment gateways / Integrations) |
| Product list | Search, status, category, **Missing image** filter, thumbs |
| Product edit | Fields above + image upload + draft / published / archived |
| Categories | Light tree editor |
| Packs | Membership multi-select (prefer published + imaged for pack quality) |
| Client | Extend `super-admin-api.ts` + `APP_ROUTES` / shell breadcrumbs |

### 7.3 Out of Phase 1

- Promote-from-Palmart
- Replace-tenant-catalogue
- Async adopt jobs
- Enforcing `platform.global_catalog.*` permission keys
- Full image backfill via Flyway
- Supplier template wiring on adopt
- New regional catalogs (existing KE/`default` resolution stays as-is)

### 7.4 Definition of done (Phase 1)

- [ ] **≥90% of the 3 starter packs have HTTPS `image_url`** (ops outcome via promote/imaging — track in SA pack strip)
- [x] SA can upload image → HTTPS `image_url` in `global-catalog/`
- [x] Status lifecycle exists: publish / unpublish / archive controls tenant browse (draft & archived hidden)
- [x] Tenant adopt creates cover **and** `item_images` (tenant-owned `public_id`) when global has image
- [x] Tenant deleting an adopted image does **not** destroy the shared global asset
- [x] Pack membership editable without SQL
- [x] List filter `missingImage` works for ops triage
- [x] IT: SA CRUD + adopt-with-image (+ pack membership, draft-hide, backfill)

---

## 8. Phase 2 — Promote from Palmart (or any curated source business)

**Outcome:** Flagship assortment + images feed `global_*` without Flyway reseeds.

### 8.1 Data-access model (critical)

SA JWT has **no tenant `business_id`**. Promote must **not** call tenant catalog APIs as the SA user.

Implement server-side:

```text
POST /api/v1/super-admin/global-catalog/promote
  → load Item(+ images) by sourceBusinessId + itemIds (repository, tenant-scoped queries)
  → upsert GlobalProduct
  → re-host images to global-catalog/
```

Impersonation is the wrong primitive for bulk promote (session confusion, audit noise). Optional later: reuse impersonation only for interactive “open shop” QA.

### 8.2 Upsert keys (ordered — align with `TenantCatalogMatchIndex`)

1. Normalized **barcode** (primary)
2. Existing `global_product_source_id` / known seed id when re-promoting the same logical SKU
3. `sku_template` / tenant SKU
4. Normalized **name + brand + size**

Default status: **`draft`**. Optional `publish: true` for trusted bulk after dry-run.

### 8.3 Backend

| Deliverable | Notes |
|-------------|--------|
| Promote API | `POST .../promote` + `POST .../promote/preview` (dry-run) |
| Input | `sourceBusinessId`, `itemIds[]`, `onConflict: update\|skip`, `publish` flag |
| Batch limit | e.g. ≤ 100 / request (async later) |
| Images | Always re-host; skip image if source has none |
| Categories | Map tenant category → global via slug hint; create global category if missing |
| Response | `created` / `updated` / `skipped` + reasons + imageRehost counts |

### 8.4 Frontend

| Deliverable | Notes |
|-------------|--------|
| “Import from business…” | From Global Catalog |
| Business picker | Any tenant; **default / pin Palmart** |
| Item picker | Thumbs, search, already-in-global badge |
| Preview | Creates / updates / skips before commit |
| Confirm | Explicit “Promote N products as drafts” |

### 8.5 Definition of done (Phase 2)

- [x] Promote N Palmart items with images → draft global rows + HTTPS `image_url`
- [x] Re-promote updates without barcode duplicates
- [x] Dry-run preview accurate vs commit
- [x] Tenant adopt of published promoted product shows cover + gallery (re-host path)
- [x] Audit log: who promoted what from which business

---

## 9. Phase 3 — Optional polish

| Item | Notes |
|------|--------|
| CSV bulk import/export | **Shipped** — `GET .../products/export.csv`, `POST .../products/import` + Curate buttons |
| Replace catalogue (empty shops only) | **Shipped** — `GET/POST .../global-catalog/replace`; soft-delete + pack adopt; blocks sales / non-zero batches |
| Async adopt / promote jobs | Large batches |
| Image backfill for already-adopted items | **Shipped** — `POST .../products/{id}/backfill-images` + Curate “Backfill adopted” |
| Categories light editor | **Shipped** — SA Categories tab |
| Supplier templates on adopt | **Shipped** — SA Suppliers tab + adopt → `GC-{code}` primary link |
| Pack ↔ onboarding kit | **Shipped** — V157 seed + SA Packs `storeKitId` + tenant meta / “for you” sort |
| Price refresh cadence | **Shipped** — Curate buy/sell/margin edit + `POST .../products/apply-margin` (page bulk) |
| `platform.global_catalog.write` | Only if non–super-admin ops roles appear |
| `business.settings.globalCatalogCode` | **Shipped** — override via business PATCH; SA business detail field; resolves before region/default |

---

## 9.1 Phase 4 — Scale & supplier depth

**Outcome:** Large batches don’t block the request thread; adopted products land with real supplier links from platform templates (not only `SYS-UNASSIGNED`).

| Item | Notes | Status |
|------|--------|--------|
| Supplier templates CRUD (SA) | List/create/patch `global_supplier_templates`; link products via `global_product_supplier_links` | **Shipped** |
| Adopt → tenant supplier | Resolve/create tenant supplier by `GC-{template.code}`; set primary `supplier_products` (+ optional cost / supplier SKU) | **Shipped** |
| Async adopt / promote jobs | Enqueue large batches; poll status (`global_catalog_jobs`, kinds `adopt`/`promote`) | **Shipped** |
| Opt-in “refresh from template” | Push updated recommended prices/images to already-adopted items | **Shipped** |
| Tenant category auto-create on adopt | Parent plan Phase B; keep opt-in | **Shipped** — `createMissingCategories` on adopt/preview/jobs; pack replace opts in |
| `platform.global_catalog.write` | Only if non–super-admin ops roles appear | Deferred (no ops roles yet) |

### Supplier adopt rules (lock)

1. Prefer the **primary** `global_product_supplier_links` row for the global product
2. Tenant supplier match key: `code = GC-{template.code}` (stable, rematchable)
3. If no template link → keep existing `SupplierLinkProvisioner` / `SYS-UNASSIGNED` behavior
4. Do **not** conflate with Marketplace suppliers (`marketplace_supplier_id`)
5. Creating a tenant supplier from a template is idempotent by code

### Definition of done (Phase 4 supplier slice)

- [x] SA can create a supplier template and attach it as primary on a global product
- [x] Adopt of that product creates/reuses `GC-…` tenant supplier and primary link
- [x] Adopt without a template link still gets `SYS-UNASSIGNED` as today
- [x] IT covers template → adopt supplier path

### Definition of done (Phase 4 async jobs)

- [x] Sync adopt limited to 25 lines; larger batches use `POST .../adopt/jobs` + poll
- [x] Sync promote limited to 100 items; larger batches use `POST .../promote/jobs` + poll
- [x] Worker drains `global_catalog_jobs` (enabled in prod/desktop; off in tests)
- [x] ITs cover enqueue → `processNext` → completed for adopt and promote

### Definition of done (Phase 4 refresh from template)

- [x] Tenant `POST .../refresh/preview` + `POST .../refresh` with opt-in sell/buy/image flags
- [x] No writes when all flags false; customized sell skip is opt-in
- [x] Images reuse `GlobalCatalogAdoptImageAttacher` (missing cover by default)
- [x] Catalog UI: select imported products → Refresh from template
- [x] IT covers adopt → template price change → refresh updates

### Definition of done (Phase 4 category auto-create)

- [x] `createMissingCategories` default false keeps uncategorized adopt when no slug match
- [x] Flag true creates tenant category with `slug = tenantCategorySlugHint` and links the item
- [x] Idempotent reuse when category already exists; batch cache for same hint
- [x] FE review dialog checkbox (off by default); pack replace opts in
- [x] IT covers create + default-off paths

---

## 9.2 Phase 5 — Regional / multi-catalog SA curation

**Outcome:** Ops can curate and promote into more than the KE `default` catalog; UG shops resolve to a published Uganda catalog via `country_code`, not KE prices by accident.

| Item | Notes | Status |
|------|--------|--------|
| `GET .../catalogs` | List platform catalogs (id, code, region, currency, status) | Shipped |
| SA `catalogId` query | Meta/products/categories/packs/suppliers/CSV/source-items; omit → `default` | Shipped |
| Promote `catalogId` | Optional on `PromoteRequest` (+ jobs payload) | Shipped |
| Empty `ug-retail` seed | Published shell: region `UG`, currency `UGX`; no product copy | Shipped |
| SA catalog picker | URL `?catalogId=` + header select | Shipped |
| Bulk clone KE → UG | Flyway `V161__CloneKeCatalogToUgRetail` + `CatalogRegionalCloneJdbc` (scrub absolute prices) | Shipped (Phase 4 onboarding scope) |
| SA create/edit catalog metadata UI | Flyway/SQL is enough for v1 | Deferred |

### Definition of done (Phase 5 regional slice)

- [x] SA can list catalogs and switch curation target via `catalogId`
- [x] Promote / CSV / packs / categories / suppliers scoped to selected catalog
- [x] Empty published `ug-retail` seeded; UG `country_code` resolves to it
- [x] Default behavior unchanged when `catalogId` omitted
- [x] ITs cover list + scoped create + region resolution
- [x] Scope §9.2 marked shipped for this slice

---

## 10. Catalog resolution (already live — document, don’t ignore)

```text
1. business.settings.globalCatalogCode (published catalog by code)
2. business.country_code → global_catalogs.region_code match (published)
3. Else catalog code = default
4. Else any published catalog
```

Phase 1–2 assumed the existing **KE / default** catalog. Phase 5 ships an empty published **UG / ug-retail** catalog and SA multi-catalog curation; resolution (override → region → default) remains the same.

---

## 11. Matching & adopt edge cases

| Case | Behavior today / expected |
|------|---------------------------|
| Same global id adopted twice | Skip (`global_product_source_id` or match index) |
| SKU conflict | Skip / rename / **merge** (`onSkuConflict`) — curation must not break merge |
| Barcode conflict on adopt | Skip |
| Draft / archived global | Invisible to tenant list APIs |
| Match without provenance | Possible via barcode/name — “already imported” can show without `global_product_source_id`; merge path can link |
| **Adopt via `onSkuConflict: merge`** | Image fix must also apply here — if the merged tenant item has no cover, add the global image (same re-host path as import). Undefined today |
| **Image registration fails during adopt** | Item still created; re-host runs post-commit and non-fatally; surface a warning on the result line, don't roll back the item |

---

## 12. Security, tenancy, audit

- SA routes bypass tenant `DomainBusinessResolverFilter` — no host tenant context (any `/api/v1/super-admin/**` path auto-bypasses; no filter change needed)
- Promote/list source items: always filter by `sourceBusinessId`; never cross-leak in responses
- Audit events: `global_product.created|updated|published|image_uploaded|promoted`. **Note: there is NO existing audit precedent at the platform layer** — integrations, payments, and business CRUD are all unaudited today; only SA login + impersonation emit events. So this is net-new via `AuditEventPublisher`. For v1, scope audit to the irreversible/provenance-sensitive ops (`promoted`, `published`, `image_uploaded`) and accept parity (unaudited) for routine edits
- Do not destroy Cloudinary assets on unpublish (archive keeps URL); explicit “clear image” only. `destroyImage(public_id)` exists and is used by tenant image delete — never point it at a shared `global-catalog/` asset (§6 warning)

---

## 13. Build order

```text
1. Adopt image fix              → images stick for tenants (cover + item_images)
2. SA product CRUD + upload     → fix data without migrations
3. Categories / packs light     → keep starter kits usable
4. missingImage ops triage      → prioritize imaging pack SKUs
5. Promote preview + promote    → fill images + fields at scale from Palmart
6. (Optional) Replace / async   → only after curation is trustworthy
```

**Do not** reseed via Flyway to “add images” after adopt traffic exists.

---

## 14. Decisions (lock these)

| # | Decision | Recommendation | Status |
|---|----------|----------------|--------|
| 1 | Promote source | Any-business picker; **default Palmart** | Proposed |
| 2 | Promote default status | `draft` | Proposed |
| 3 | Image ownership | Always re-host into `global-catalog/` | Proposed |
| 4 | Conflict on promote | Upsert by barcode (`update`); skip if `onConflict=skip` | Proposed |
| 5 | Who can promote | Super Admin only (v1) | Proposed |
| 6 | Adopt image strategy | Re-host into tenant Cloudinary folder on adopt (**mandatory** — direct global `public_id` on tenant rows is unsafe: tenant delete → `destroyImage` kills the shared asset). Use remote-URL fetch, not byte streaming | Proposed |
| 7 | Barcode uniqueness | **DB-enforced** via generated `dedup_barcode` column + unique index (archived exempt); app check alone races and can't hit the "0 duplicates" metric | Proposed |
| 8 | Phase 1 scope | Ship pack-imaging slice first; defer field-level CRUD (promote overwrites it) | Proposed |
| 9 | Categories/packs concurrency | No `version` column — accept last-write-wins for v1, or add version | Proposed |

---

## 15. Test plan (minimum)

| Case | Layer |
|------|--------|
| SA create/patch product + version conflict | IT |
| SA image upload → HTTPS URL, not `/api/media` | IT |
| Publish hides draft from tenant `GET .../products` | IT |
| Adopt with image → `image_key` + `item_images` (tenant-owned `public_id`, not global) | IT |
| **Adopt via merge → image added when merged item lacks a cover** | IT |
| **Tenant deletes adopted image → global `global-catalog/` asset survives** (no shared-`public_id` destroy) | IT |
| Adopt without image → no broken gallery | IT |
| **Concurrent promote of same barcode → exactly one row** (exercises `dedup_barcode` unique index) | IT |
| Promote preview vs promote counts match | IT |
| Promote upsert same barcode → one row | IT |
| Pack membership change visible in tenant meta | IT / manual |

---

## 16. Effort sketch (revised)

| Phase | Scope | Notes |
|-------|--------|--------|
| Phase 1 | SA controller + adopt image re-host + status lifecycle + SA UI | Upload to `global-catalog/` is trivial (folder API exists). The hard parts are the **adopt re-host + post-commit safety**, the **net-new status lifecycle**, and the **`dedup_barcode` index**. Trim per §7.0 |
| Phase 2 | Promote preview/commit + SA item listing by business + re-host | Needs careful tenancy queries; mirror the `REQUIRES_NEW` per-line + `message` pattern for partial-failure isolation |
| Phase 3 | As needed | Async / suppliers deferred; CSV + pack↔kit + backfill + replace + price refresh + **globalCatalogCode** shipped |

---

## 17. Failure modes & rollback

| Failure | Response |
|---------|----------|
| Bad promote batch | Products land as **draft** — unpublish/archive; do not mass-delete if tenants might have linked ids |
| Wrong image uploaded | Replace via image endpoint; old CDN asset can orphan (acceptable v1) |
| Adopt image registration fails | Item still created; surface warning on adopt result line; ops can fix global URL |
| Accidental publish | Set `draft`/`archived`; tenant browse filters published only |

---

## 18. Parent plan sync

Update mental model vs `GLOBAL_PRODUCTS_CATALOG_PLAN.md`:

| Plan said | Now |
|-----------|-----|
| Status: Scoping / Phase A future | Tenant Phase A **shipped** |
| Admin APIs under `/api/v1/platform/...` | Use **`/api/v1/super-admin/global-catalog/...`** |
| Admin UI `/platform/global-catalog` | **`/super-admin/platform/global-catalog`** |
| Seed ~150 SKUs | **~2.4k** seeded; quality/images are the gap |
| Admin = SQL only | Still true until Phase 1 of **this** doc |

---

## 19. Key file pointers

| Area | Path |
|------|------|
| This scope | `backend/docs/SUPER_ADMIN_GLOBAL_CATALOG_SCOPE.md` |
| Parent plan | `backend/docs/GLOBAL_PRODUCTS_CATALOG_PLAN.md` |
| Marketplace (do not conflate) | `backend/docs/SUPPLIER_MARKETPLACE_SCOPE.md` |
| Tenant adopt API | `backend/.../globalcatalog/api/GlobalCatalogController.java` |
| Adopt orchestration | `backend/.../globalcatalog/application/GlobalCatalogService.java` |
| Adopt line executor | `backend/.../globalcatalog/application/GlobalCatalogAdoptLineExecutor.java` |
| Match index | `backend/.../globalcatalog/application/TenantCatalogMatchIndex.java` |
| Domain | `backend/.../globalcatalog/domain/GlobalProduct.java` |
| Seed script | `backend/scripts/generate_global_catalog_seed.py` |
| Tenant catalog UI | `frontend/app/(dashboard)/products/catalog/page.tsx` |
| SA shell / nav | `frontend/components/super-admin/super-admin-shell.tsx` |
| SA API client | `frontend/lib/super-admin-api.ts` |
| Item images | `backend/.../catalog/domain/ItemImage.java` |
| Cloudinary | `backend/...` `CloudinaryImageService` (tenant folders today) |
| SA auth pattern | Payments / integrations controllers under `/api/v1/super-admin/` |
