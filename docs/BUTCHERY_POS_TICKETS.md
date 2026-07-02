# Butchery POS — Implementation Tickets

Derived from `BUTCHERY_POS_SCOPE.md`. Tracks **open backend/product work** and **shipped frontend** for the butcher vertical.

**Scope doc:** see [§0 Implementation Status](./BUTCHERY_POS_SCOPE.md#0-implementation-status) for the full shipped vs gap matrix.

---

## Shipped — frontend & role (no further tickets)

These are **done** in the repo; listed here so open tickets are not duplicated.

| ID | Deliverable | Key files |
|----|-------------|-----------|
| **FE-1** | `butcher_cashier` role + login → `/butcher` | `V125__butcher_cashier_role.sql`, `post-auth-destination.ts` |
| **FE-2** | Branch lock for `butcher_cashier` | `BranchResolutionService.java`, `branch-access.ts` |
| **FE-3** | Butcher shell (dark chrome, brand theme, nav) | `butcher-shell.tsx`, `butcher-nav.tsx`, `butcher-pos-chrome.ts` |
| **FE-4** | Counter POS `/butcher` — pills, grid, weight sheet, charge sale | `butcher-cashier-workspace.tsx`, `butcher-product-tile.tsx`, `butcher-pos.ts` |
| **FE-5** | Suppliers `/butcher/suppliers` — split-pane UI | `butcher-suppliers-workspace.tsx`, `app/butcher/suppliers/page.tsx` |
| **FE-6** | Add stock modal — live totals, draft vs receive | `butcher-add-stock-dialog.tsx`, `purchase-unit-conversion.ts` |
| **FE-8** | Draft resume + partial receive in add stock | `butcher-add-stock-dialog.tsx`, `fetchPathBSessions` |
| **FE-9** | `butcher_pos.enabled` nav wiring | `butcher-feature.ts`, `app-shell.tsx` |
| **FE-10** | Counter hold/recall, cash change, split pay; supplier tags from `item_types`; catalog weighed templates | `butcher-cashier-workspace.tsx`, `butcher-suppliers-workspace.tsx`, `ProductCreateDrawer.tsx`, `butcher-product-templates.ts` |
| **FE-11** | Variable-weight barcode scan on `/butcher` | `butcher-cashier-workspace.tsx`, `fetchVariableWeightBarcode`, `VariableWeightBarcodeParser.java` |
| **FE-7** | App shell route gates + redirect `cashier` / `butcher_cashier` | `app-shell.tsx`, `cashier-shell.tsx`, `shell-page-titles.ts` |

### Shipped behaviour notes (for QA / support)

- Counter uses `GET /api/v1/items` + `fetchPosShelfPrice` + `POST /api/v1/sales` (same pipeline as retail POS).
- Add stock **Save as draft** = Path B `SESSION_DRAFT` only; **Receive stock** = `postPathBSession` (inventory + AP).
- **Open orders per supplier** can be listed from Path B drafts (`GET /api/v1/purchasing/path-b/sessions?supplierId=&status=draft`) or Path A sent POs (`GET /api/v1/purchasing/path-a/purchase-orders?supplierId=&status=sent`). Path B is the canonical butcher add-stock path; Path A PO receive (GRN + invoice) is available in the add-stock dialog when the user has `purchasing.path_a.write`.
- **Partial receive** is supported for Path B: post a subset of lines and the session stays `SESSION_DRAFT` with the remaining lines `LINE_PENDING`.
- Purchase **unit** uses `supplier_products.pack_unit` / `pack_size` when set; server validates on Path B post when `purchaseQty` + `purchaseUnit` are sent.
- **Cost/unit** on receive is buy price; margin % vs shelf price shown per line in add-stock dialog.
- Card payment on butcher counter posts as `card` tender (card clearing ledger; no drawer cash).

---

## Open — backend & product

Tickets below are ordered by dependency (foundational first).

---

## Ticket 1 — `butcher_cashier` branch lock
**Phase:** P2  
**Owner:** Backend  
**Status:** ✅ **Done** (see FE-2)

### Problem
`BranchResolutionService` only locked `stock_manager`, `cashier`, and `grocery_clerk` to their assigned branch. A `butcher_cashier` user could switch branches or fail branch resolution.

### Acceptance criteria
- [x] Add `"butcher_cashier"` to `BranchResolutionService.BRANCH_LOCKED_ROLE_KEYS`.
- [x] Update JavaDoc/comments that mention branch-locked roles to include butcher cashiers.
- [x] Add unit test verifying branch-locked resolution for `butcher_cashier`.

### Files
- `src/main/java/zelisline/ub/tenancy/application/BranchResolutionService.java`
- `src/test/java/zelisline/ub/tenancy/application/BranchResolutionServiceTest.java`

---

## Ticket 2 — Server-side weighed sale line validation
**Phase:** P2  
**Owner:** Backend  
**Status:** ✅ **Done**

### Problem
`SaleService` currently accepts any positive `BigDecimal` quantity for any item. There is no enforcement that weighed items use weight units, or that non-weighed items are sold with integer quantities. The shipped `/butcher` UI sends decimal kg client-side only.

### Acceptance criteria
- [x] Weighed items (`is_weighed = true`) must have `unit_type` in `{kg, g, lb}` (v1 enforces `kg` only).
- [x] Weighed sale lines must have `quantity` with max scale 3 for kg.
- [x] `unit_price` on weighed lines must be price per kg.
- [x] Non-weighed items must have integer quantity (scale 0).
- [x] Return a clear `400 BAD_REQUEST` with field-level errors.
- [x] Add integration tests for valid weighed lines, too many decimals, and non-integer qty for non-weighed items.

### Files
- `src/main/java/zelisline/ub/sales/application/SaleService.java`
- `src/main/java/zelisline/ub/catalog/domain/Item.java` (existing fields)
- `src/test/java/zelisline/ub/sales/application/SaleServiceTest.java`

---

## Ticket 3 — Receipt unit labels for weighed lines
**Phase:** P2  
**Owner:** Backend  
**Status:** ✅ **Done**

### Problem
Receipt renderers print `0.347 x 1200.00 = 416.40` with no unit label. Butchery customers need to see `kg`, `g`, or `lb`.

### Acceptance criteria
- [x] Add `unit_type` to `ReceiptLineRow`.
- [x] Format weighed lines as `{qty} {unit} x {unit_price} = {line_total}` (e.g., `0.347 kg x 1200.00 = 416.40`).
- [x] Non-weighed lines keep current integer format.
- [x] Update both `ReceiptEscPosRenderer` and `ReceiptPdfRenderer`.
- [x] Add renderer tests for weighed and non-weighed lines.

### Files
- `src/main/java/zelisline/ub/sales/receipt/ReceiptLineRow.java`
- `src/main/java/zelisline/ub/sales/receipt/ReceiptSnapshot.java`
- `src/main/java/zelisline/ub/sales/receipt/ReceiptEscPosRenderer.java`
- `src/main/java/zelisline/ub/sales/receipt/ReceiptPdfRenderer.java`
- `src/test/java/zelisline/ub/sales/receipt/ReceiptEscPosRendererTest.java`

---

## Ticket 4 — Catalog endpoint for butcher workspace
**Phase:** P2  
**Owner:** Backend  
**Status:** ✅ **Done** (existing API; no new endpoint)

### Problem
The `/butcher` workspace needs a catalog list for cut tiles (search, category pills, weighed/piece filtering).

### Resolution
Frontend reuses **`GET /api/v1/items`** with `catalogScope=SKUS_ONLY`, `itemTypeId`, search `q`, `branchId`, and client-side `resolveButcherSellBy()` in `butcher-pos.ts`. No dedicated `butcher-catalog` endpoint was required for v1.

### Acceptance criteria
- [x] Reuse `GET /api/v1/items` with query params: `itemTypeId`, search, `branchId`, sellable SKUs.
- [x] Response fields used: `id`, `name`, `barcode`, `isWeighed`, `unitType`, `bundlePrice`, `stockQty`, `imageKey` / `thumbnailUrl`, `itemTypeId`.
- [x] Verify RBAC in integration test: `butcher_cashier` can call the endpoint with `catalog.items.read`.

### Files
- `frontend/components/butcher/butcher-cashier-workspace.tsx`
- `frontend/lib/butcher-pos.ts`
- `src/main/java/zelisline/ub/catalog/api/ItemsController.java`

---

## Ticket 5 — Weighed item catalog admin backend validation
**Phase:** P1  
**Owner:** Backend  
**Status:** ✅ **Done**

### Problem
The item create/patch API allows `is_weighed = true` with any `unit_type`. We need to enforce weight-compatible units.

### Acceptance criteria
- [x] When `is_weighed = true`, `unit_type` must be one of `{kg, g, lb}`.
- [x] `400 BAD_REQUEST` returned on create/patch if invalid.
- [x] Integration tests for create and patch validation.

### Files
- Item create/patch validators (find current validator or add one in `catalog/application`)

---

## Ticket 6 — Butchery vertical flag / feature flag
**Phase:** P0/P1  
**Owner:** Backend + Product  
**Status:** ✅ **Done**

### Problem
The doc references `business_type = butcher` but no such field exists in the backend today. Grocery nav is not auto-hidden for butcher tenants.

### Resolution
Use a feature flag (`butcher_pos.enabled`) stored in `businesses.settings` JSON. This avoids a schema migration and is consistent with existing flags (pos_drafts, grocery_drafts, etc.).

### Acceptance criteria
- [x] Decision: use `FeatureFlagService` flag `butcher_pos.enabled`.
- [x] Add `FLAG_BUTCHER_POS_ENABLED` + `isButcherPosEnabled(...)` helper.
- [x] Extend `FeatureFlagsPatchRequest` and `StorefrontSettingsService.mergeFeatureFlags` so the flag can be toggled via `PATCH /api/v1/businesses/me`.
- [x] Expose flag in `BusinessResponse.featureFlags` and `PublicHostResolveResponse.featureFlags`.
- [x] **Frontend:** `app-shell.tsx` hides grocery nav when flag is on; shows **Butcher counter** link in Sales section and header POS links.
- [x] Add unit tests for read and merge.

### Files
- `src/main/java/zelisline/ub/tenancy/application/FeatureFlagService.java`
- `frontend/lib/butcher-feature.ts`
- `frontend/components/app-shell.tsx`
- `src/main/java/zelisline/ub/tenancy/api/dto/FeatureFlagsPatchRequest.java`
- `src/main/java/zelisline/ub/tenancy/application/StorefrontSettingsService.java`
- `src/test/java/zelisline/ub/tenancy/application/FeatureFlagServiceTest.java`
- `src/test/java/zelisline/ub/tenancy/application/StorefrontSettingsServiceFeatureFlagTest.java`

---

## Ticket 7 — Line-level price override permission
**Phase:** P2  
**Owner:** Backend + Product  
**Status:** ✅ **Done**

### Decision
Reuse existing `pricing.sell_price.set` instead of introducing `sales.weighed.override_price`. Manager and admin roles already hold this permission (see `V22__phase3_pricing_slice5.sql`); standard cashiers and `butcher_cashier` do not. A future frontend manager-PIN flow can re-authenticate as a user with this permission when a cashier tries to override.

### Acceptance criteria
- [x] Decision: line-level override gated by existing `pricing.sell_price.set` permission.
- [x] `SaleService.validateSaleLines(...)` resolves the current open selling price for each line (branch-specific, business-wide, then `item.bundlePrice`) and rejects the sale with `403 FORBIDDEN` when the posted `unitPrice` differs by more than `0.01` and the actor lacks `pricing.sell_price.set`.
- [x] Controllers pass the actor's `roleId` through `createSale` so the permission check is request-accurate.
- [x] Integration tests for weighed items: shelf-price sale succeeds, override without permission is forbidden, override with permission succeeds.

### Files
- `src/main/java/zelisline/ub/sales/application/SaleService.java`
- `src/main/java/zelisline/ub/sales/api/SalesController.java`
- `src/main/java/zelisline/ub/posdraft/application/PosDraftService.java`
- `src/main/java/zelisline/ub/posdraft/api/PosDraftController.java`
- `src/main/java/zelisline/ub/grocery/application/GroceryInvoiceService.java`
- `src/main/java/zelisline/ub/grocery/api/GroceryInvoiceController.java`
- `src/test/java/zelisline/ub/sales/api/SaleSlice2IT.java`

---

## Ticket 8 — Weighed refund policy
**Phase:** P2  
**Owner:** Product + Backend  
**Status:** ✅ **Done**

### Decisions
- **Yes**, weighed items can be refunded.
- Refunds may be **partial weight** (e.g. customer returns 0.2 kg of a 0.5 kg sale).
- Returned meat is **not restocked** for food-safety reasons; it is written off as wastage.
- Weighed refunds require the **`sales.weighed.refund`** permission, granted by default to `owner`, `admin`, and `manager`.

### Acceptance criteria
- [x] Decision: weighed items refundable, full or partial weight.
- [x] Decision: returned meat written off as wastage.
- [x] New permission `sales.weighed.refund` seeded and granted to owner/admin/manager.
- [x] `SaleRefundService.createRefund(...)` rejects weighed refund attempts with `403 FORBIDDEN` when the actor lacks the permission.
- [x] Weighed refund lines skip the normal restock path; a zero-delta `refund_wastage` stock movement is recorded with `WastageReason.CUSTOMER_RETURN` for audit.
- [x] Refund journal debits `INVENTORY_SHRINKAGE` for weighed COGS instead of `INVENTORY`; non-weighed refunds keep the existing restock journal.
- [x] Integration tests for denied weighed refund, full weighed refund with wastage, and partial weighed refund.

### Files
- `src/main/java/zelisline/ub/sales/application/SaleRefundService.java`
- `src/main/java/zelisline/ub/sales/api/SalesController.java`
- `src/main/java/zelisline/ub/inventory/WastageReason.java`
- `src/main/java/zelisline/ub/inventory/InventoryConstants.java`
- `src/main/resources/db/migration/V126__weighed_refund_permission.sql`
- `src/test/java/zelisline/ub/sales/api/SaleSlice2IT.java`

---

## Ticket 9 — Variable-weight barcode parser (P3)
**Phase:** P3  
**Owner:** Backend + Frontend  
**Status:** ✅ **Done** (enable per tenant in business settings)

### Problem
No variable-weight barcode parser exists. Scale labels encode PLU + weight/price. Shipped `/butcher` scan field does not intercept variable-weight EAN yet.

### Acceptance criteria
- [x] Add `plu_code` column to `items`.
- [x] Add business setting for barcode format (prefix length, PLU position, weight digit count, price digit count).
- [x] Implement `VariableWeightBarcodeParser`.
- [x] Integrate scan intercept in `/butcher` and public barcode lookup.
- [x] Error clearly on unknown PLU or mismatched item type.
- [x] Products admin: **Scale PLU** field on create/edit for weighed items.

### Files
- `src/main/java/zelisline/ub/sales/application/VariableWeightBarcodeParser.java`
- `src/main/java/zelisline/ub/sales/application/VariableWeightBarcodeService.java`
- `src/main/java/zelisline/ub/catalog/domain/Item.java`
- `src/main/java/zelisline/ub/storefront/application/PublicBarcodeLookupService.java`
- `frontend/components/butcher/butcher-cashier-workspace.tsx`
- `frontend/lib/api.ts` (`fetchVariableWeightBarcode`)
- `frontend/app/(dashboard)/products/_components/ProductCreateDrawer.tsx`, `ProductEditDrawer.tsx`
- `V127__items_plu_code.sql`

### Tenant config
Enable in `businesses.settings`:

```json
{
  "butcher": {
    "variableWeightBarcode": {
      "enabled": true,
      "prefixDigit": "2",
      "pluStart": 1,
      "pluLength": 5,
      "valueStart": 6,
      "valueLength": 5,
      "embeddedField": "weight",
      "weightUnit": "grams",
      "validateCheckDigit": true
    }
  }
}
```

Assign `pluCode` on weighed items (products admin or API). Scan a 13-digit prefix-2 label on `/butcher` to add a kg line with parsed weight.

---

## Ticket 10 — Scale integration (P4)
**Phase:** P4  
**Owner:** Backend + Frontend + Hardware  
**Status:** 🟡 **In progress** — Web Serial v1 shipped; validate on named hardware

### Problem
Highest engineering risk. Depends on scale brand/protocol. Butcher weight sheet uses manual entry only.

### v1 decision (see `BUTCHERY_SCALE_V1.md`)
- **Transport:** Web Serial API (Chrome / Edge desktop)
- **Protocol:** Generic continuous ASCII lines @ 9600 baud
- **Stable gate:** 600 ms ±2 g, or hardware `S` / `ST` prefix
- **Tare:** Per-session in browser

### Acceptance criteria
- [x] Choose v1 scale brand/model and protocol (USB/RS-232).
- [x] Implement local bridge or Web Serial integration.
- [x] Tare handling (per session / per container SKU).
- [x] Stable-weight gate before accepting weight.
- [x] Document supported hardware.
- [ ] Field-validate on customer-named scale model.

### Files
- `backend/docs/BUTCHERY_SCALE_V1.md`
- `frontend/lib/butcher-scale.ts`, `butcher-scale.test.ts`
- `frontend/hooks/use-butcher-serial-scale.ts`
- `frontend/components/butcher/butcher-cashier-workspace.tsx` (weight sheet)

---

## Ticket 11 — Draft stock session list & partial receiving
**Phase:** P2+  
**Owner:** Frontend + Backend  
**Status:** ✅ **Done**

### Decision
Keep **Path B** as the canonical butcher add-stock path (fast receipt from supplier), but expose both Path B drafts and Path A sent POs so the frontend can show a unified “open orders” list per supplier.

### Acceptance criteria
- [x] List open Path B draft sessions per supplier: `GET /api/v1/purchasing/path-b/sessions?supplierId={id}&status=draft`.
- [x] List sent Path A POs per supplier: `GET /api/v1/purchasing/path-a/purchase-orders?supplierId={id}&status=sent`.
- [x] Existing `GET /api/v1/purchasing/path-b/sessions/{id}` provides full detail for resuming/editing before receive.
- [x] Path B partial receive: `POST /api/v1/purchasing/path-b/sessions/{id}/post` accepts a subset of lines; session stays `SESSION_DRAFT` until all lines received.
- [x] **Frontend:** `butcher-add-stock-dialog.tsx` — open orders panel, resume draft, per-line receive checkboxes, patch/delete lines on save.
- [x] Supplier detail shows draft count badge.

### Files
- `frontend/components/butcher/butcher-add-stock-dialog.tsx`
- `frontend/components/butcher/butcher-suppliers-workspace.tsx`
- `frontend/lib/api.ts` (`fetchPathBSessions`, `fetchPathAPurchaseOrders`)
- `src/main/java/zelisline/ub/purchasing/application/PathBPurchaseService.java`

---

## Ticket 12 — Purchase unit conversion hardening
**Phase:** P2  
**Owner:** Frontend + Backend  
**Status:** ✅ **Done** (uses existing `supplier_products.pack_unit` / `pack_size`)

### Problem
`purchase-unit-conversion.ts` maps crate/tray/weight on the client. Ambiguous units fall back with a toast; server `resolveInbound` may disagree if package variants are missing.

### Acceptance criteria
- [x] Store supplier-specific purchase unit + conversion on `supplier_products` (`pack_unit`, `pack_size` — schema already existed).
- [x] Server validates posted `usableQty` against purchase unit on receive (`purchaseQty` + `purchaseUnit` on Path B post).
- [x] Admin UI to set buy unit per supplier–product link (Suppliers → edit link drawer).

### Files
- `frontend/lib/purchase-unit-conversion.ts`
- `frontend/components/butcher/butcher-add-stock-dialog.tsx`
- `frontend/app/(dashboard)/suppliers/_components/SupplierCatalogColumn.tsx`
- `src/main/java/zelisline/ub/purchasing/application/PurchaseUnitConversionService.java`
- `src/main/java/zelisline/ub/purchasing/application/PathBPurchaseService.java`
- `src/main/java/zelisline/ub/suppliers/application/ItemSupplierLinkService.java`

---

## Ticket 13 — Butcher counter UX gaps
**Phase:** P2+  
**Owner:** Frontend  
**Status:** ✅ **Done** — POS draft-backed hold/recall, split pay, change due, supplier category tags, catalog weighed UX, card tender

### Problem
Shipped counter lacks features called out in scope Phases 2–4.

### Acceptance criteria
- [x] Held / suspended orders on `/butcher` (POS drafts-backed when enabled)
- [x] Split payment + change due
- [x] Portrait tablet layout polish (order panel height on md)
- [x] Card tender (`card` payment method → card clearing ledger, not drawer cash)
- [x] Supplier category tags from `item_types` not item name heuristics
- [x] Catalog create: “Price per kg” + meat quick templates (Phase 1 UX)

### Files
- `butcher-cashier-workspace.tsx`
- `butcher-suppliers-workspace.tsx`
- `ProductCreateDrawer.tsx`, `ProductCreatePricingSection.tsx`, `butcher-product-templates.ts`

---

## Deferred / future

- **P5:** Pre-packed + online shop filtering — ✅ **Done** (see `BUTCHERY_P5_P6_ANALYTICS.md`)
- **P6:** Carcass breakdown / production BOM — planned; ~4–8 weeks
- **Analytics:** kg-sold reports, shrinkage by reason, margin per kg — planned; ~1–2 weeks
- **Retail `/cashier` weighed UX:** optional; butcher tenants should use `/butcher` instead.
