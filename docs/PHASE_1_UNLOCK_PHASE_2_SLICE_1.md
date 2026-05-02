# Phase 1 → Phase 2 Slice 1 handoff checklist

**Goal:** Close only what **Phase 2** actually depends on before coding **Slice 1 — Supplier aggregate & item links** (`PHASE_2_PLAN.md` §Slice 1 and §Prerequisites).

**Not the full Phase 1 wishlist** — items here are *blocking* or *strongly coupled* to suppliers. Optional polish stays in `PHASE_1_PLAN.md`.

Status legend: **Done** · **Gap** · **Partial**

---

## A. Data the supplier model hangs on

| # | Gate | Status | Notes |
|---|------|--------|--------|
| 1 | `items` table stable (PKs, `business_id`, `is_sellable`, `is_stocked`, soft-delete rules) | **Done** | `V8__catalog_items.sql` + `Item` entity |
| 2 | Path to **list / PATCH items** for linking UI and APIs | **Done** | `ItemsController`, permissions `catalog.items.*` |
| 3 | **Backfill plan** before “primary supplier required” (synthetic supplier or owner-driven import) | **Gap** | Phase 2 §Prereq + `PHASE_1_PLAN.md` risk #10 — document migration + optional `Phase2HandoffIT` before strict DB trigger |
| 4 | **Branches** if Phase 2 receipts are branch-scoped | **Gap** | Table `branches` exists (`V1__tenancy_core.sql`); **no** `GET`/`POST /api/v1/branches` in app/OpenAPI yet — add if Slice 2+ needs `branch_id` on purchases without raw UUIDs |

---

## B. Security & tenancy

| # | Gate | Status | Notes |
|---|------|--------|--------|
| 5 | Tenant resolution (`Host` → business, JWT aligns) | **Done** | `DomainBusinessResolverFilter`, `JwtAuthenticationFilter` |
| 6 | Tenant isolation on existing tables | **Partial** | Per-area ITs (`UsersApiIT`, catalog ITs); no single **`TenantIsolationIT`** matrix as in Phase 1 doc — consider before widening surface |
| 7 | **New permission keys** for suppliers (additive Flyway) | **Gap** | Not in `V3`/`V6` seeds — Phase 2 expects e.g. `suppliers.read`, `suppliers.write`, `catalog.items.link_suppliers` (exact set → one ADR + `role_permissions` updates) |

---

## C. Cross-cutting Phase 1 promises Phase 2 reuses

| # | Gate | Status | Notes |
|---|------|--------|--------|
| 8 | **`activity_log`** on successful mutations | **Gap** | Not in `src/` or migrations — Phase 2 §Cross-cutting expects create/link/PO/GRN/invoice/payment logging |
| 9 | **`Idempotency-Key`** on mutating routes | **Partial** | Wired for **`POST /api/v1/items`**; super-admin **`POST .../businesses`** and most other `POST`/`PATCH` are not — expand to routes Phase 2 will add (`breakdown`, GRN, payment, …) and to high-risk Phase 1 creates if you want parity |
|10 | **Events / outbox** (`supplier.created`, `goods.received`, …) | **Gap** | Only a comment in `SecurityConfig`; no outbox tables or relay — Phase 2 §Prereq lists `platform-events` |
|11 | **ADR `0006-permissions-as-data.md`** | **Gap** | `docs/adr/` currently has `0007-token-rotation.md` only |

---

## D. Catalog behaviour supplier work will assume

| # | Gate | Status | Notes |
|---|------|--------|--------|
|12 | Item search good enough for operator UX | **Partial** | `FULLTEXT` index exists (`V8`); `ItemRepository.search` uses **JPQL `LIKE`** — no `MATCH…AGAINST` fast path; acceptable for Slice 1 if operators mostly pick items by id, SKU, or barcode |
|13 | Category **merge** API | **Optional** | Phase 1 doc places merge in Slice 5 — **not** required for supplier spine |
|14 | Item image upload | **Partial** | Client supplies `s3Key`; no signed PUT from a storage adapter — optional unless supplier docs reuse the same flow |

---

## Recommended order before the first `suppliers` migration

1. **Permissions + ADR** (#7, #11): additive Flyway for supplier (and link) keys; grant owner/admin/manager as appropriate; add `0006-permissions-as-data.md` (or fold into an existing ADR) so Phase 2 keys stay data-driven.
2. **Branches API** (#4): if Slice 2 Path B will send `branch_id`, ship list/create endpoints or document “UUID only from DB” for the pilot.
3. **Backfill strategy** (#3): decide synthetic supplier name + migration sketch *before* any `NOT NULL` / trigger that requires a primary supplier per item.
4. **Activity log** (#8): minimal `activity_log` table + write on mutations you already have (user create, item write) so Phase 2 only extends the pattern.
5. **Outbox** (#10): smallest viable `outbox_events` + post-commit publish (or document deferral with an explicit ADR if Slice 1 ships without events).
6. **Idempotency** (#9): define which Phase 2 writes must be idempotent first; reuse the `idempotency_keys` pattern where `business_id` applies (super-admin creates may need a parallel key scope).
7. **Tenant isolation matrix** (#6): expand IT coverage before `supplier_products` fans out join paths.

---

## Quick reference

| Doc | Role |
|-----|------|
| `docs/PHASE_2_PLAN.md` | Phase 2 scope, slices, DoD — [§ Prerequisites](PHASE_2_PLAN.md#-prerequisites--phase-1-must-close-first) links back here |
| `docs/PHASE_1_PLAN.md` | Full Phase 1 contract |
| `docs/openapi/phase-1.yaml` | Phase 1 HTTP contract (extend or add `phase-2.yaml` for suppliers) |

*Last aligned to repo snapshot: 2026-05-02.*
