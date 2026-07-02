# Butchery deferred work — P5, P6, Analytics

Roadmap for the three remaining butcher vertical milestones after counter POS (P2–P4).

---

## P5 — Pre-packed + online shop filtering ✅ Shipped

**Goal:** `/shop` works for butcher tenants: pre-packed trays online; weighed counter cuts in-store only.

### Shipped behaviour

| Layer | Rule |
|-------|------|
| API | `onlinePurchaseMode`: `web_cart` (default) or `in_store_only` (`is_weighed = true`) |
| Cart | Rejects weighed items + non-integer quantities |
| Shop UI | “In store” badge; no add-to-cart; PDP explains counter-only |

### Files

- `StorefrontOnlinePurchaseRules.java`
- `PublicStorefrontCatalogService.java`, `PublicWebCartService.java`
- Public catalog DTOs (`onlinePurchaseMode`)
- `shop-product-grid.tsx`, `shop-add-to-cart.tsx`, `app/[sku]/page.tsx`
- Tests: `StorefrontOnlinePurchaseRulesTest`, `PublicWebCartIT`

### Not in P5 (future polish)

- Butcher-specific category nav labels (Meat / Poultry / Deli) — use existing `item_types` departments
- Hide weighed items entirely from catalog (tenant setting) — currently show with badge

---

## P6 — Carcass breakdown / production BOM

**Goal:** Breakdown shops receive bulk primals (e.g. 45 kg side) and post output cut SKUs + trim wastage with reconciled stock and cost.

**Effort:** ~4–8 weeks · **Separate product** from counter POS

### Why deferred

PalMart has batch inventory, wastage write-offs, and Path B receive — but **no transform/BOM** domain. P6 needs net-new aggregates and GL rules.

### Proposed domain (v1 design)

```
BreakdownSession
  id, businessId, branchId, inputBatchId, inputItemId, inputQtyKg
  status: draft | posted
  postedAt, postedBy, notes

BreakdownOutputLine
  sessionId, itemId, outputQtyKg, allocatedUnitCost

BreakdownWastageLine
  sessionId, reason (trim/spoilage), qtyKg
```

### Posting (on approve)

1. Debit output SKUs (inventory batches per cut, cost allocated by weight %)
2. Credit input batch (reduce remaining qty on primal batch)
3. Wastage → `INVENTORY_SHRINKAGE` / existing wastage movement (no restock)
4. Optional journal: reclassify bulk COGS to cut COGS

### Permissions (seed)

| Key | Roles |
|-----|-------|
| `butcher.breakdown.run` | manager, admin, custom butcher role |
| `butcher.breakdown.approve` | owner, admin |

### UI surfaces

- Dashboard: **Production → Breakdown** (not `/butcher` counter)
- Wizard: pick input batch → add output lines → wastage → preview yield % → post

### Exit criteria

- 45 kg side → 8 cut SKUs + 5 kg trim; stock and batch costs reconcile within ±0.5%
- Yield report: input kg, output kg, wastage kg, yield %

### Dependencies

- P2 weighed validation (done)
- Batch traceability (exists)
- Ticket: create `BUTCHERY_P6_BREAKDOWN_SPEC.md` before coding

---

## Analytics — kg sold, margin per kg, shrinkage

**Goal:** Butcher managers see counter performance: kg sold by cut, margin vs buy price, shrinkage by reason.

**Effort:** ~1–2 weeks (reporting slice; no new inventory domain)

### Data already available

| Metric | Source |
|--------|--------|
| Kg sold | `sale_items` × `items.is_weighed`, `quantity`, `unit_type` |
| Revenue per kg | `sale_items.unit_price` on weighed lines |
| Buy cost per kg | `inventory_batches.unit_cost` from FEFO pick on sale |
| Margin % | `(sell - cost) / sell` per line or rolled up |
| Shrinkage | `stock_movements` with `WastageReason` |

### Proposed API (v1)

```
GET /api/v1/sales/intelligence/weighed-summary?from=&to=&branchId=
→ { totalKgSold, weighedRevenue, weighedCogs, grossMarginPct, lineCount }

GET /api/v1/sales/intelligence/weighed-by-item?from=&to=&branchId=&limit=20
→ [{ itemId, itemName, kgSold, revenue, cogs, marginPct }]

GET /api/v1/inventory/intelligence/shrinkage-by-reason?from=&to=&branchId=
→ [{ reason, qtyKg, value }]
```

Permission: reuse `sales.intelligence.read` (manager+).

### UI

- **Analytics** page section: “Butcher counter” (when `butcher_pos.enabled`)
- Table: top cuts by kg + margin; chart: daily kg sold
- Link from `/butcher` suppliers margin hints (already per-line on receive)

### Exit criteria

- Manager sees last 30 days kg sold and margin % for weighed SKUs
- Shrinkage breakdown includes `CUSTOMER_RETURN` weighed refunds (wastage path from Ticket 8)

### Implementation order

1. `WeighedSalesIntelligenceService` + controller + IT
2. Frontend analytics cards + table
3. Optional: export CSV

---

## Suggested sequence

| Order | Milestone | When |
|-------|-----------|------|
| 1 | **P5** shop filtering | ✅ Done |
| 2 | **Analytics** v1 | Next — low risk, uses existing sales/batch data |
| 3 | **P6** breakdown | When a customer commits to Tier D / production shop |

---

## Related docs

- `BUTCHERY_POS_SCOPE.md` §9–10
- `BUTCHERY_POS_TICKETS.md` deferred section
- `BATCH_TRACEABILITY_ANALYSIS.md` — batch pick on sale
