# Batch Traceability & Product Tracking Analysis

> **Project**: UB (Palmart) — Spring Boot 3 / Next.js POS & Inventory System  
> **Date**: 2025-06-06  
> **Scope**: Evaluating whether the current implementation supports per-batch and per-item traceability end-to-end, from supplier receipt through sale (and back through void/refund).

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Domain Model Overview](#2-domain-model-overview)
3. [Core Entities and Their Roles](#3-core-entities-and-their-roles)
4. [End-to-End Traceability Chains](#4-end-to-end-traceability-chains)
5. [What Works Well](#5-what-works-well)
6. [Gaps and Limitations](#6-gaps-and-limitations)
7. [The "1000 Eggs" Scenario Walkthrough](#7-the-1000-eggs-scenario-walkthrough)
8. [Concurrency & Correctness](#8-concurrency--correctness)
9. [Recommendations](#9-recommendations)
10. [Database Schema Reference](#10-database-schema-reference)

---

## 1. Executive Summary

**The system implements strong batch-level traceability.** Every supply order, whether received via formal PO (Path A) or market trip breakdown (Path B), creates one or more uniquely identified `inventory_batches` rows. When a sale occurs, each `sale_item` explicitly records which `batch_id` it pulled stock from. Voids and refunds correctly restore or re-allocate batch inventory with full audit trail via `stock_movements`.

**However, the system stops at batch-level granularity.** If you receive 1,000 eggs in a single batch, all 1,000 eggs share one batch identity. You cannot identify individual eggs within that batch. This is standard for grocery/retail POS systems but may fall short if you need serial-number-level tracking (e.g., electronics, pharmaceuticals, high-value items).

**Critical strengths:**
- Immutable `stock_movements` audit trail capturing every inventory delta
- Pessimistic locking preventing batch oversell
- Configurable FEFO/FIFO/LIFO batch pick strategies
- Full double-entry GL integration mirroring every stock movement

**Identified gaps:**
- No per-item unique identification within a batch
- Refunded items create *new* batches rather than restoring to the original batch
- Wastage is tracked at item-level, not batch-level
- No "damaged item" status within a batch

---

## 2. Domain Model Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           INBOUND                                            │
│                                                                              │
│  Path A (Formal PO)                 Path B (Market Trip)                     │
│  ┌──────────────────┐              ┌───────────────────────┐                 │
│  │ PurchaseOrder     │              │ RawPurchaseSession     │                 │
│  │  ├─ PurchaseOrderLine │          │  ├─ RawPurchaseLine    │                 │
│  │  └─ GoodsReceipt  │              │  └─ Breakdown (post)   │                 │
│  │      └─ GoodsReceiptLine│        │      ├─ usableQty ──▶ batch           │
│  │          └─ batch  │              │      └─ wastageQty ──▶ movement      │
│  └──────────────────┘              └───────────────────────┘                 │
│                                                                              │
│  Path C (Opening Balances) / Stock Gains                                    │
│  ┌──────────────────────────────────────────────┐                            │
│  │ InventoryLedgerService.recordOpeningBalance() │                           │
│  │ InventoryLedgerService.recordStockIncrease()  │                           │
│  └──────────────────────────────────────────────┘                            │
│                                                                              │
│                    ▼  ▼  ▼                                                  │
│         ┌─────────────────────────┐                                         │
│         │    InventoryBatch       │  ◀── The core unit of stock identity     │
│         │  • id (UUID)            │                                         │
│         │  • batch_number         │                                         │
│         │  • item_id              │                                         │
│         │  • supplier_id          │                                         │
│         │  • source_type / id     │  ◀── Links back to origin document       │
│         │  • initial_quantity     │                                         │
│         │  • quantity_remaining   │  ◀── Decremented on sale/void/refund     │
│         │  • unit_cost            │                                         │
│         │  • expiry_date          │                                         │
│         │  • status               │  ◀── active | (inactive/deactivated)     │
│         │  • version (optimistic) │                                         │
│         └─────────────────────────┘                                         │
│                    │                                                         │
│         ┌──────────┴──────────┐                                             │
│         │   StockMovement     │  ◀── Append-only audit trail                │
│         │  • batch_id         │      Every inventory delta creates one row   │
│         │  • movement_type    │      (receipt | sale | sale_void | refund    │
│         │  • quantity_delta   │       | wastage | adjustment | transfer)     │
│         │  • reference_type/id│                                             │
│         │  • created_by       │                                             │
│         └─────────────────────┘                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                           OUTBOUND                                           │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  SaleService.createSale()                                            │   │
│  │    │                                                                  │   │
│  │    ├─ InventoryBatchPickerService.pickAndApplyPhysicalDecrement()    │   │
│  │    │    ├─ Lock all active batches (PESSIMISTIC_WRITE, ID-ordered)   │   │
│  │    │    ├─ Sort by FEFO → FIFO → LIFO                                │   │
│  │    │    ├─ Allocate qty across batches                               │   │
│  │    │    ├─ Decrement batch.quantity_remaining                        │   │
│  │    │    ├─ Decrement item.current_stock                              │   │
│  │    │    └─ Write StockMovement rows (movement_type = "sale")         │   │
│  │    │                                                                  │   │
│  │    ├─ Build SaleItem rows with batch_id per allocation               │   │
│  │    ├─ Post GL journal (Dr COGS, Cr Inventory; Dr Cash/Bank, Cr Revenue)│  │
│  │    └─ Apply credit/debt, wallet, loyalty effects                     │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  SaleVoidService.voidSale()                                          │   │
│  │    ├─ Walk all SaleItem rows                                         │   │
│  │    ├─ FOR EACH: restore qty to referenced batch                      │   │
│  │    ├─ Write StockMovement (movement_type = "sale_void")              │   │
│  │    ├─ Post reversal journal                                          │   │
│  │    └─ Reverse credit/debt, wallet, loyalty                           │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  SaleRefundService.createRefund()                                    │   │
│  │    ├─ Walk requested SaleItem lines (partial qty allowed)            │   │
│  │    ├─ Create NEW InventoryBatch (source_type = "refund_return")      │   │
│  │    ├─ Write StockMovement (movement_type = "refund")                 │   │
│  │    ├─ Post refund journal                                            │   │
│  │    └─ Adjust credit/debt, wallet, loyalty proportionally             │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Core Entities and Their Roles

### 3.1 `InventoryBatch` (`inventory_batches` table)

The **fundamental unit of stock identity**. Each row represents a distinct lot of a specific item received at a specific time from a specific source.

| Field | Role in Traceability |
|---|---|
| `id` | UUID — globally unique batch identifier. Referenced by `SaleItem.batch_id`, `StockMovement.batch_id`, `GoodsReceiptLine.inventory_batch_id` |
| `batch_number` | Human-readable label like `"A-ABCD1234"` (Path A) or `"B-EFGH5678"` (Path B) — useful for physical labelling |
| `item_id` | Links to the catalog `Item` (product/SKU) |
| `supplier_id` | Links to the `Supplier` who provided this batch |
| `source_type` + `source_id` | Bi-directional link: `"path_a_grn"` → `GoodsReceiptLine.id`, `"path_b_breakdown"` → `RawPurchaseLine.id`, `"opening_balance"` → synthetic ID, `"refund_return"` → `Refund.id` |
| `initial_quantity` | How much was originally received |
| `quantity_remaining` | How much is still available — decremented by sales, incremented by voids |
| `unit_cost` | Landed unit cost — used for COGS calculation and profit margin |
| `expiry_date` | Drives FEFO pick strategy |
| `status` | `"active"` or deactivated (post-expiry or fully consumed) |
| `version` | `@Version` field — optimistic locking to prevent concurrent modification |

### 3.2 `StockMovement` (`stock_movements` table)

The **append-only immutable audit trail**. Every inventory delta creates exactly one row.

| Movement Type | Trigger | quantity_delta Sign | batch_id |
|---|---|---|---|
| `receipt` | Path A GRN posted / Path B breakdown | Positive (+) | ✅ Links to batch |
| `sale` | POS sale completed | Negative (−) | ✅ Links to batch |
| `sale_void` | Sale voided (restore) | Positive (+) | ✅ Links to batch |
| `refund` | Sale refunded (return to stock) | Positive (+) | ✅ Links to NEW batch |
| `wastage` | Path B wastage / standalone wastage | Negative (−) | ❌ NULL |
| `opening` | Opening balance recorded | Positive (+) | ✅ Links to batch |
| `adjustment` | Stock count gain / manual decrease | ± | ✅ Links to batch |
| `transfer_out` | Stock sent to another branch | Negative (−) | ✅ Links to batch |
| `transfer_in` | Stock received from another branch | Positive (+) | ✅ Links to batch |

### 3.3 `SaleItem` (`sale_items` table)

The **critical junction** between a sale and the batch it consumed from.

| Field | Role |
|---|---|
| `sale_id` | Links to the parent `Sale` |
| `item_id` | The catalog item sold |
| `batch_id` | **The specific batch the stock came from** — enables full traceability |
| `quantity` | How many units from this batch were sold |
| `unit_price` | Selling price per unit |
| `unit_cost` | Landed cost from the batch |
| `line_total` | Revenue portion for this allocation |
| `cost_total` | COGS for this allocation |
| `profit` | `line_total - cost_total` — per-batch profitability |

**Each `PostSaleLineRequest` (one cart line) can produce multiple `SaleItem` rows** if the allocation spans multiple batches. For example, selling 5 eggs might produce:
- `SaleItem(batch_id="B-AAA", qty=3)` — 3 from batch B-AAA
- `SaleItem(batch_id="B-BBB", qty=2)` — 2 from batch B-BBB

---

## 4. End-to-End Traceability Chains

### 4.1 Forward Traceability (Supplier → Sale)

```
Supplier
  └─ PurchaseOrder / RawPurchaseSession
       └─ GoodsReceiptLine / RawPurchaseLine (breakdown)
            └─ InventoryBatch (id = "BATCH-001")
                 └─ StockMovement (movement_type = "receipt", qty = +100)
                      └─ SaleItem (batch_id = "BATCH-001", qty = 5)
                           └─ Sale
                                └─ StockMovement (movement_type = "sale", qty = −5)
```

**Query to trace a batch forward to sales:**
```sql
SELECT si.*, s.sold_at, s.id AS sale_id
FROM sale_items si
JOIN sales s ON s.id = si.sale_id
WHERE si.batch_id = 'BATCH-001';
```

### 4.2 Backward Traceability (Sale → Supplier)

```
Sale (id = "SALE-001")
  └─ SaleItem (batch_id = "BATCH-001", qty = 5)
       └─ InventoryBatch (id = "BATCH-001")
            ├─ source_type = "path_b_breakdown"
            ├─ source_id → RawPurchaseLine
            │    └─ RawPurchaseSession
            │         └─ Supplier
            └─ source_type = "path_a_grn"
                 └─ GoodsReceiptLine
                      └─ GoodsReceipt
                           └─ PurchaseOrder
                                └─ Supplier
```

**Query to trace a sale backward to supplier:**
```sql
SELECT si.*, ib.batch_number, ib.source_type, ib.source_id, ib.supplier_id
FROM sale_items si
JOIN inventory_batches ib ON ib.id = si.batch_id
WHERE si.sale_id = 'SALE-001';
```

### 4.3 Void Restoration

```
SaleVoidService.voidSale("SALE-001"):
  FOR EACH SaleItem(batch_id="BATCH-001", qty=5):
    InventoryBatch.quantity_remaining += 5   ← restored to ORIGINAL batch
    StockMovement(movement_type="sale_void", qty=+5, batch_id="BATCH-001")
```

✅ **Void correctly restores quantity to the original batch.**

### 4.4 Refund Chain

```
SaleRefundService.createRefund("SALE-001"):
  Creates NEW InventoryBatch:
    batch_number = "RFND-XXXXXXXX"
    source_type = "refund_return"
    source_id = <refund_id>
    quantity = refunded_qty
    
  StockMovement(movement_type="refund", qty=+3, batch_id="RFND-XXXXXXXX")
```

⚠️ **Refund creates a new batch rather than restoring to the original batch.**  
This is an intentional design choice (discussed below in §6.2).

---

## 5. What Works Well

### 5.1 Batch-Level Traceability Is Complete

Every `sale_item` knows which `batch_id` it drew from. You can answer questions like:
- "Which supplier provided the eggs in this sale?"
- "Which batches were consumed in this sale?"
- "When did this batch arrive, and what was its unit cost?"
- "What's the total COGS and profit per batch used in this sale?"

### 5.2 Immutable Audit Trail

`stock_movements` is append-only — no rows are ever updated or deleted (except via the FK cascade from `inventory_batches`). The `created_at` field is immutable. Every inventory state can be reconstructed by replaying movements chronologically.

### 5.3 Pessimistic Locking Prevents Oversell

`InventoryBatchPickerService.pickAndApplyPhysicalDecrement()`:
1. Locks all batches for the item at the branch with `PESSIMISTIC_WRITE`
2. Sorts by ID for deadlock avoidance (consistent lock ordering across concurrent transactions)
3. Allocates by policy (FEFO → FIFO → LIFO)
4. Decrements `quantity_remaining` and saves
5. Writes `stock_movements`

The `@Version` column provides an additional safety net in case the pessimistic lock is bypassed.

### 5.4 Configurable Batch Pick Strategy

| Strategy | Logic | Use Case |
|---|---|---|
| **FEFO** | Sort by `expiry_date ASC`, then `received_at ASC` | Perishable goods (eggs, milk, produce) |
| **FIFO** | Sort by `received_at ASC` | Standard retail (non-perishable) |
| **LIFO** | Sort by `received_at DESC` | Wholesale / bulk where newest stock is most accessible |

FEFO is auto-selected when `item.has_expiry = true` AND at least one batch has an `expiry_date`.

### 5.5 Double-Entry GL Integration

Every stock movement posts corresponding journal entries:
- **Sale**: Dr COGS (expense), Cr Inventory (asset); Dr Cash (asset), Cr Revenue (income)
- **Void**: Full reversal of the original sale journal
- **Refund**: Mirrors the void journal for the refunded portion
- **Receipt**: Dr Inventory (asset), Cr GRNI/AP (liability)

This means your financial reports always match your physical inventory.

### 5.6 Multi-Tenant Isolation

Every domain entity carries `business_id`. Repositories scope queries by `business_id`. Controllers derive `business_id` from the authenticated tenant context. No cross-tenant data leakage is possible at the query level.

---

## 6. Gaps and Limitations

### 6.1 No Per-Item Unique Identification Within a Batch

**The system treats a batch as an indivisible unit of stock identity.**

If you receive **1,000 eggs** in one `InventoryBatch`, there is no way to:
- Identify or track individual egg #427
- Mark egg #427 as damaged without affecting the whole batch
- Know which specific egg went into which specific sale beyond "it came from batch B-ABCD1234"

**Is this a problem?** For most grocery/retail use cases — **no**. For high-value items (electronics, jewelry, pharmaceuticals, livestock) — **yes**, you'd need serial number or RFID-level tracking.

**What would be needed for per-item tracking:**
- A new `StockItem` or `SerialNumber` entity (one row per individual item within a batch)
- Each `StockItem` would have its own status (`available`, `sold`, `damaged`, `stolen`, `expired`)
- `SaleItem` would reference `stock_item_id` instead of (or in addition to) `batch_id`
- Barcode/RFID scanning at POS would need to capture the individual item identifier

### 6.2 Refunds Create New Batches (Broken Original-Batch Link)

**Current behavior:** When a customer returns an item, `SaleRefundService.createRefund()` creates a **new** `InventoryBatch` with `source_type = "refund_return"` rather than incrementing the original batch's `quantity_remaining`.

**Implication:** After a refund:
```
Before sale:     Batch-001 (qty = 100)
After sale:      Batch-001 (qty =  95)  ← 5 sold
After refund:    Batch-001 (qty =  95)  ← unchanged!
                 Batch-002 (qty =   3)  ← NEW "refund_return" batch
```

This means:
- You **cannot** trace a refunded item back to its original supplier/batch via the batch ID alone — you need to follow `refund → sale_item → batch_id` to find the original.
- The original batch's `quantity_remaining` never reflects returned goods.
- The refunded batch has no `supplier_id` link (it was set by `SaleRefundService` as null or derived).

**Why this design?** Likely to preserve the immutability of the original receipt — the batch represents "what was received from the supplier," and adding returned goods would distort that. However, for full traceability, one could argue that returned goods should re-enter the original batch.

### 6.3 Wastage Not Linked to Specific Batch

Both Path B wastage (`applyLinePost` → wastage `StockMovement`) and standalone wastage (`recordStandaloneWastage`) set `batch_id = NULL` on the `StockMovement`.

This means:
- You can see "5 kg of tomatoes wasted" but cannot tell which batch they came from.
- Wastage cannot be traced to a specific supplier or receipt date.
- If you have multiple batches of the same item, you don't know which one lost stock.

**Fix suggested:** Wastage should either:
- Target a specific batch (decrement `quantity_remaining`, link `batch_id`)
- Or the system should use the batch picker to decide which batch to deduct from (FEFO for wastage makes sense — expire the oldest first).

### 6.4 No "Damaged" or "Stolen" Status Within a Batch

There is no mechanism to mark a subset of a batch as:
- **Damaged but recoverable** (e.g., cracked eggs that could be used in baking)
- **Stolen** (theft, for shrinkage accounting)
- **Quarantined** (held for quality inspection)

All wastage goes through the same `movement_type = "wastage"` with a `reason` text field. The `reason` field provides some categorization but is free-text and not enforced.

### 6.5 Stock Take Reconciliation Is Periodic, Not Real-Time

The `StockTakeSession` → `StockAdjustmentRequest` system catches discrepancies during physical counts, but this is an offline, periodic process. If an item goes missing between counts, there's no real-time detection.

### 6.6 Expiry Auto-Deactivation Requires Owner Confirmation

Per `implement.md` §7.5:
> "A batch with `expiry_date < now()` is auto-deactivated and its remaining qty is moved to `wastage` with reason `expired`, **only after owner confirms** (no silent write-off)."

This is a deliberate safety measure, but it means expired stock might sit in the system until someone manually approves the write-off. A nightly job only *notifies*, it doesn't auto-deactivate.

---

## 7. The "1000 Eggs" Scenario Walkthrough

Let's trace 1,000 eggs through the system, testing every path.

### 7.1 Receipt (Path B — Market Trip)

```
User creates RawPurchaseSession:
  supplier: "Farm Fresh Eggs Ltd"
  branch: "Main Shop"
  
User adds line:
  description: "1000 eggs @ 15 KES each"
  amount: 15,000 KES

User posts breakdown:
  item: "Eggs (Large)" (item_id = "ITEM-EGGS")
  usable_qty: 980    ← 20 eggs cracked/unsellable
  wastage_qty: 20
```

**What happens:**
```
InventoryBatch created:
  id = "BATCH-001"
  batch_number = "B-ABCD1234"
  item_id = "ITEM-EGGS"
  supplier_id = "SUP-FARMFRESH"
  source_type = "path_b_breakdown"
  source_id = <RawPurchaseLine.id>
  initial_quantity = 980
  quantity_remaining = 980
  unit_cost = 15.3061 KES  (15,000 / 980)
  expiry_date = <set by user in breakdown>

StockMovement (receipt):
  batch_id = "BATCH-001"
  movement_type = "receipt"
  quantity_delta = +980
  unit_cost = 15.3061

StockMovement (wastage):
  batch_id = NULL        ← ⚠️ NOT linked to batch!
  movement_type = "wastage"
  quantity_delta = -20
  reason = "Path B wastage"
```

### 7.2 Sales (Multiple Transactions)

#### Sale 1: Customer buys 3 eggs

```
InventoryBatchPickerService:
  1. Lock BATCH-001 (only active batch)
  2. Allocate 3 from BATCH-001
  3. BATCH-001.quantity_remaining = 980 → 977

StockMovement (sale):
  batch_id = "BATCH-001"
  movement_type = "sale"
  quantity_delta = -3

SaleItem:
  sale_id = "SALE-001"
  item_id = "ITEM-EGGS"
  batch_id = "BATCH-001"     ← ✅ Traceable!
  quantity = 3
  unit_price = 25 KES
  line_total = 75 KES
  unit_cost = 15.3061
  cost_total = 45.9183
  profit = 29.0817 KES
```

#### Sale 2: Customer buys 10 eggs

Same as above, BATCH-001 goes to 967 remaining.

#### After 100 sales... 

BATCH-001.quantity_remaining = 0 (all 980 eggs sold).

✅ **Every one of the 980 eggs is traceable through `SaleItem(batch_id="BATCH-001")` back to the purchase session and supplier.**  
❌ **But you cannot distinguish which specific egg went to which customer — only that all came from BATCH-001.**

### 7.3 Void Scenario

Customer returns 2 minutes later — "I changed my mind, please void sale SALE-050" (3 eggs).

```
SaleVoidService.voidSale("SALE-050"):
  SaleItem(batch_id="BATCH-001", qty=3)
  
  InventoryBatch.quantity_remaining += 3   ← 977 → 980
  
  StockMovement(sale_void):
    batch_id = "BATCH-001"
    movement_type = "sale_void"
    quantity_delta = +3
```

✅ **Void correctly restores to the original batch. Traceability chain is unbroken.**

### 7.4 Refund Scenario

Customer returns 2 eggs the next day (not void — refund).

```
SaleRefundService.createRefund():
  
  New InventoryBatch created:
    id = "BATCH-002"
    batch_number = inferred
    source_type = "refund_return"
    source_id = <Refund.id>
    initial_quantity = 2
    quantity_remaining = 2
    supplier_id = NULL         ← ⚠️ Lost supplier link!
    unit_cost = <from original sale_item>

  StockMovement(refund):
    batch_id = "BATCH-002"     ← Links to NEW batch, not original
    movement_type = "refund"
    quantity_delta = +2

  BATCH-001.quantity_remaining = unchanged (still at whatever it was)
```

⚠️ **The refunded eggs are now in BATCH-002, which has no `supplier_id`.**  
To trace these back to "Farm Fresh Eggs Ltd," you must follow:
`BATCH-002` → `StockMovement(refund)` → `Refund` → `SaleItem(batch_id="BATCH-001")` → `InventoryBatch(BATCH-001)` → `supplier_id`.

### 7.5 Wastage / Damage Scenario

"5 eggs were found cracked in storage."

```
InventoryLedgerService.recordStandaloneWastage():
  item_id = "ITEM-EGGS"
  quantity = 5
  unit_cost = 15.3061
  reason = "Cracked in storage"

StockMovement(wastage):
  batch_id = NULL             ← ⚠️ Not linked to any batch
  movement_type = "wastage"
  quantity_delta = -5

JournalEntry:
  Dr Inventory Shrinkage (5210) = 76.53 KES
  Cr Inventory (1200) = 76.53 KES
```

⚠️ **BATCH-001.quantity_remaining is NOT decremented.** The wastage is an item-level adjustment that reduces `Item.current_stock` but does not affect any batch. This creates a discrepancy: the sum of all batch `quantity_remaining` values no longer equals `Item.current_stock`.

**To fix this**, wastage should allocate to a specific batch using the same FEFO/FIFO picker logic that sales use.

### 7.6 Expiry Scenario

"One month later, 50 unsold eggs have expired."

Per `implement.md` §7.5:
1. Nightly job detects `expiry_date <= now()`
2. Dashboard notification raised
3. **Owner must manually confirm** the write-off
4. On confirmation: batch deactivated + wastage movement created

⚠️ **Until owner confirms, expired stock sits as "active" inventory**, inflating `Item.current_stock` and potentially being picked for sales (if the FEFO comparator didn't de-prioritize expired dates).

---

## 8. Concurrency & Correctness

### 8.1 Locking Strategy

```
InventoryBatchPickerService.pickAndApplyPhysicalDecrement():
  
  1. Lock Item row (PESSIMISTIC_WRITE)     ← Prevents concurrent stock updates
  2. Lock all active batches for item      ← Prevents concurrent batch modifications
     (PESSIMISTIC_WRITE, ordered by ID)    ← ID ordering avoids deadlocks
  3. Sort by FEFO/FIFO/LIFO
  4. Allocate quantity across batches
  5. Decrement each batch.quantity_remaining
  6. Save batches
  7. Write StockMovement rows
  8. Decrement Item.current_stock
  9. Save Item
```

**Deadlock avoidance:** Batches are always locked in ID order (`findBy...OrderByIdAsc`), ensuring consistent lock acquisition order across all concurrent transactions.

**Optimistic locking fallback:** `InventoryBatch` has `@Version` — if a batch is somehow modified between the pessimistic lock and the save, the version mismatch will cause a rollback.

### 8.2 Sale Item → Batch Integrity

The `sale_items.batch_id` column has a `FOREIGN KEY` constraint to `inventory_batches(id)`. It is impossible to record a sale against a non-existent or deleted batch.

### 8.3 Void → Batch Restoration Integrity

`SaleVoidService.restoreInventory()`:
1. Sorts `SaleItem` rows by `(item_id, batch_id)` — consistent ordering
2. Locks each unique `Item` with `PESSIMISTIC_WRITE` (only once per item)
3. Locks each batch with `findByIdAndBusinessIdForUpdate` (implicit `PESSIMISTIC_WRITE`)
4. Validates batch belongs to the same branch as the sale
5. Adds quantity back and saves

### 8.4 Idempotency Keys

Both sales and refunds support idempotency keys (`idempotency_key` + unique constraint on `(business_id, idempotency_key)`). This prevents double-processing if the client retries after a network timeout.

Path A GRN and Path B post also use idempotency with body-hash verification.

---

## 9. Recommendations

### 9.1 High Priority (Correctness)

| # | Issue | Recommendation |
|---|---|---|
| 1 | **Wastage not linked to batch** | Modify `recordStandaloneWastage()` and Path B wastage to use the batch picker (FEFO first for wastage), decrement `batch.quantity_remaining`, and set `batch_id` on the `StockMovement`. |
| 2 | **Refund batch has no supplier_id** | When creating a refund batch, copy `supplier_id` from the original `SaleItem`'s batch. This preserves the supplier link chain. |
| 3 | **Expired batches can still be picked** | The FEFO comparator should **exclude** batches where `expiry_date < today` or add a filter: only consider batches where `status = 'active' AND (expiry_date IS NULL OR expiry_date >= CURRENT_DATE)`. |
| 4 | **Wastage doesn't reconcile with batch totals** | `SUM(batch.quantity_remaining)` should equal `Item.current_stock`. Currently, wastage bypasses batch accounting. Fixing #1 resolves this. |

### 9.2 Medium Priority (Traceability Enhancement)

| # | Issue | Recommendation |
|---|---|---|
| 5 | **No per-item tracking** | If individual item tracking is required (high-value goods), introduce a `StockItem` entity with per-item UUIDs, unique barcodes/RFID tags, and per-item status (`available`, `sold`, `damaged`, `stolen`, `expired`, `quarantined`). This is a significant schema change. |
| 6 | **Free-text wastage reasons** | Add a `wastage_reason` enum or lookup table with categories: `spoilage`, `breakage`, `theft`, `sample`, `personal_use`, `expired`. Enforce at API level. |
| 7 | **Refund restores to new batch instead of original** | Consider adding a configuration option: `refund.restore_to_original_batch = true/false`. When true, increment original batch's `quantity_remaining` instead of creating a new batch. |

### 9.3 Low Priority (Nice to Have)

| # | Issue | Recommendation |
|---|---|---|
| 8 | **No damaged-item quarantine** | Add `INVENTORY_BATCH.status` values: `active`, `quarantined`, `expired`, `depleted`. Quarantined batches are excluded from sale picks but remain in stock valuation. |
| 9 | **No real-time theft detection** | If per-item tracking is implemented, a missing-item check during stock-take could flag items that were never sold but are missing (theft). |
| 10 | **Batch merge on refund to original** | If recommendation #7 is adopted, handle the case where the original batch may have been partially depleted — refunds would restore to whatever is left of the original. |

---

## 10. Database Schema Reference

### Key Tables for Batch Traceability

```
inventory_batches
├── id (PK)                    ← Globally unique batch identifier
├── business_id                ← Tenant isolation
├── branch_id                  ← Location scoping
├── item_id (FK → items)       ← Catalog product
├── supplier_id (FK → suppliers) ← Supplier (nullable for refund/opening batches)
├── batch_number               ← Human-readable label
├── source_type                ← "path_a_grn" | "path_b_breakdown" | "opening_balance" | "refund_return" | "stock_count_gain" | "stock_transfer"
├── source_id                  ← FK to the source document
├── initial_quantity           ← Original received qty
├── quantity_remaining         ← Current available qty
├── unit_cost                  ← Landed cost per unit
├── expiry_date                ← For FEFO picking
├── status                     ← "active" | ... (other statuses)
└── version                    ← Optimistic lock

stock_movements
├── id (PK)
├── business_id
├── branch_id
├── item_id (FK → items)
├── batch_id (FK → inventory_batches, NULLABLE)  ← ⚠️ NULL for wastage!
├── movement_type              ← "receipt" | "sale" | "sale_void" | "refund" | "wastage" | "opening" | "adjustment" | "transfer_out" | "transfer_in"
├── reference_type             ← "goods_receipt_line" | "raw_purchase_line" | "sale" | "sale_void" | "refund" | "inventory_operation" | "stock_transfer_line" | "stock_adjustment_request"
├── reference_id               ← FK to the reference document
├── quantity_delta             ← Positive for inbound, negative for outbound
├── unit_cost
├── reason                     ← Free-text (consider enum)
├── notes
├── created_at
└── created_by (FK → users)

sale_items
├── id (PK)
├── sale_id (FK → sales)
├── line_index
├── item_id (FK → items)
├── batch_id (FK → inventory_batches)  ← THE CRITICAL LINK
├── quantity
├── unit_price
├── line_total
├── unit_cost                  ← From the batch
├── cost_total
└── profit                     ← line_total - cost_total

purchase_orders
├── id (PK)
├── business_id
├── supplier_id (FK → suppliers)
├── branch_id
├── po_number
├── status                     ← "draft" | "sent" | "cancelled"
└── ...

purchase_order_lines
├── id (PK)
├── purchase_order_id (FK → purchase_orders)
├── item_id (FK → items)
├── qty_ordered
├── qty_received               ← Updated on GRN post
└── ...

goods_receipts
├── id (PK)
├── purchase_order_id (FK → purchase_orders)
├── status                     ← "draft" | "posted"
└── ...

goods_receipt_lines
├── id (PK)
├── goods_receipt_id (FK → goods_receipts)
├── purchase_order_line_id (FK → purchase_order_lines)
├── qty_received
├── inventory_batch_id (FK → inventory_batches)  ← Link to created batch
└── ...

raw_purchase_sessions
├── id (PK)
├── supplier_id (FK → suppliers)
├── branch_id
├── status                     ← "draft" | "posted" | "cancelled"
└── ...

raw_purchase_lines
├── id (PK)
├── session_id (FK → raw_purchase_sessions)
├── line_status                ← "pending" | "posted"
├── posted_item_id (FK → items)
├── usable_qty
├── wastage_qty
├── inventory_batch_id (FK → inventory_batches)  ← Link to created batch
└── ...
```

---

## Summary

| Aspect | Status | Notes |
|---|---|---|
| Batch-level traceability | ✅ Complete | Every sale records its batch, every batch records its source |
| Stock movement audit trail | ✅ Complete | Append-only, immutable, every delta captured |
| Void traceability | ✅ Complete | Restores to original batch with audit |
| Refund traceability | ⚠️ Partial | New batch created, supplier link lost, original batch not restored |
| Wastage traceability | ❌ Weak | Not linked to specific batch, doesn't decrement batch quantity |
| Per-item identification | ❌ Not implemented | Only batch-level granularity |
| Concurrency safety | ✅ Strong | Pessimistic + optimistic locking, deadlock-avoidant ordering |
| GL integration | ✅ Complete | Double-entry journals for every inventory movement |
| Expiry management | ⚠️ Partial | FEFO picking works, but expired batches may not be auto-excluded |

**Overall verdict:** The system implements a solid, production-grade batch-level inventory tracking system that is suitable for most grocery, retail, and wholesale use cases. The key gaps are in wastage tracking (not batch-linked) and the lack of per-item identification within a batch — the latter being a fundamental architectural choice that would require significant schema changes to address.
