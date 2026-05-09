# Supply Batch Redesign — Multi-Item Batch Tracking

> **Project**: UB (Palmart) — Spring Boot 3  
> **Date**: 2025-06-06  
> **Status**: Design — not yet implemented  
> **Motivation**: A batch should be a **container** for multiple items, not a single-item row.  
> "I receive 1,000 eggs, 50 crates of milk, and 200 loaves of bread in one delivery. That's Batch #42.  
> I want to open Batch #42 and see: eggs → 300 sold, 20 waste, 680 left. Milk → 40 sold, 2 waste, 8 left. Bread → all sold."

---

## Table of Contents

1. [The Problem — What the Current Model Gets Wrong](#1-the-problem--what-the-current-model-gets-wrong)
2. [The Target Model](#2-the-target-model)
3. [Entity Relationship Diagram](#3-entity-relationship-diagram)
4. [New Entity: `SupplyBatch`](#4-new-entity-supplybatch)
5. [Changes to Existing Entities](#5-changes-to-existing-entities)
6. [Migration Strategy](#6-migration-strategy)
7. [New API Endpoints](#7-new-api-endpoints)
8. [Query Patterns — Answering Business Questions](#8-query-patterns--answering-business-questions)
9. [Step-by-Step Implementation](#9-step-by-step-implementation)
10. [Frontend Implications](#10-frontend-implications)
11. [What Happens to the Existing Batch Number / Source Tracking](#11-what-happens-to-the-existing-batch-number--source-tracking)
12. [How This Integrates with the Four Gaps from the Previous Guide](#12-how-this-integrates-with-the-four-gaps-from-the-previous-guide)
13. [Estimated Effort](#13-estimated-effort)

---

## 1. The Problem — What the Current Model Gets Wrong

### Current schema (simplified)

```
InventoryBatch (1 row = 1 item in 1 batch)
├── id               "BATCH-A"
├── item_id          "ITEM-EGGS"              ← only ONE item per row
├── batch_number     "A-ABCD1234"             ← unique per-row, not shared
├── source_type      "path_a_grn"
├── source_id        <GoodsReceiptLine.id>    ← links to the line, not the header
├── quantity_remaining
├── unit_cost
└── supplier_id
```

### What happens when you receive 3 items in one delivery

```
GoodsReceipt #GRN-001
├── GoodsReceiptLine #GRNL-001 → InventoryBatch "BATCH-A"  (item: eggs,   qty: 1000)
├── GoodsReceiptLine #GRNL-002 → InventoryBatch "BATCH-B"  (item: milk,   qty: 50)
└── GoodsReceiptLine #GRNL-003 → InventoryBatch "BATCH-C"  (item: bread,  qty: 200)
```

### What you CAN'T do today

| You want to... | Current state |
|---|---|
| **Name** the delivery "Tuesday Market Run #7" | ❌ No place for a user-friendly name |
| **See** all items that arrived together in one delivery | ⚠️ Only by querying `source_type` + `source_id` across unrelated batch rows |
| **Track** a delivery as a unit — "is Batch #42 fully sold?" | ❌ No "batch header" to track |
| **Report** on per-batch performance — "which batches had highest wastage?" | ❌ No batch header to group by |
| **Navigate** from a sale item up to the delivery it came from | ⚠️ Requires `sale_item → inventory_batch → goods_receipt_line → goods_receipt` — a 4-hop join |

### The naming confusion

The word "batch" currently means **one item's lot**. A delivery of 10 items = 10 "batches."  
The user (and any normal person) thinks of "batch" as **the whole delivery** — the crate of goods that arrived together.

---

## 2. The Target Model

**Two levels:**

```
Level 1:  SupplyBatch  ───   The HEADER. One per delivery / purchase trip.
           "Tuesday Market Run #7" — received from "Farm Fresh Eggs" on 2025-06-05

Level 2:  InventoryBatch  ───  The LINES. One per item within that delivery.
           ├─ item: Eggs    (received 1000, remaining 680, sold 300, wasted 20)
           ├─ item: Milk    (received 50,  remaining 8,   sold 40,  wasted 2)
           └─ item: Bread   (received 200, remaining 0,   sold 200, wasted 0)
```

**Key rule:** `InventoryBatch` rows that share the same `supply_batch_id` represent items that arrived together. The `SupplyBatch` is the container.

---

## 3. Entity Relationship Diagram

```
┌───────────────────────────────────────────────────────────────────────────┐
│  BEFORE (Current)                                                         │
│                                                                           │
│  GoodsReceipt / RawPurchaseSession     InventoryBatch                     │
│  ├── id (source_id)                   ├── id                             │
│  └── ...                              ├── item_id        ← 1 item       │
│                                       ├── source_type    ← "path_a_grn" │
│                                       ├── source_id      ← line ID      │
│                                       └── batch_number   ← unique       │
│                                                                           │
│  ⚠️ No header entity that groups batches. source_type + source_id is the  │
│     implicit group, but it's buried in the purchasing domain.             │
└───────────────────────────────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────────────────────────────┐
│  AFTER (Target)                                                           │
│                                                                           │
│  SUB.PURCHASING     sub.INVENTORY (new)        sub.INVENTORY (existing)   │
│                                                                           │
│  GoodsReceipt        SupplyBatch                 InventoryBatch           │
│  ├── id              ├── id                     ├── id                    │
│  └── ...             ├── batch_number  ← "B-7"  ├── supply_batch_id (FK) │
│                      ├── batch_name    ← name   ├── item_id               │
│  RawPurchaseSession  ├── supplier_id            ├── quantity_remaining    │
│  ├── id              ├── received_at            ├── unit_cost             │
│  └── ...             ├── status                 ├── ... (unchanged)       │
│                      ├── source_type            └── batch_number → REMOVE │
│                      └── source_id                                     │
│                             ↑                                              │
│                      (links back to GRN or RPS for full audit trail)      │
│                                                                           │
│  KEY: SupplyBatch is the NEW header. InventoryBatch loses its             │
│       batch_number and gains supply_batch_id. source_type + source_id     │
│       move to SupplyBatch (from InventoryBatch).                          │
└───────────────────────────────────────────────────────────────────────────┘
```

---

## 4. New Entity: `SupplyBatch`

### Java Entity

**New file:** `backend/src/main/java/zelisline/ub/inventory/domain/SupplyBatch.java`

```java
package zelisline.ub.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * A SupplyBatch is the HEADER entity for a group of items that arrived
 * together in a single delivery / purchase trip.
 *
 * <p>Each row in this table represents one "batch" as a business user
 * understands it: "Batch #42 — Tuesday market run from Farm Fresh."
 *
 * <p>The individual items within this batch are tracked as
 * {@link InventoryBatch} rows, each linked back via {@code supplyBatchId}.
 *
 * <p>source_type + source_id link back to the originating document:
 * "path_a_grn" → GoodsReceipt, "path_b_session" → RawPurchaseSession,
 * "opening" → synthetic, "stock_gain" → synthetic, etc.
 */
@Getter
@Setter
@Entity
@Table(name = "supply_batches")
public class SupplyBatch {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    /** Null for opening-balance and stock-gain batches. */
    @Column(name = "supplier_id", length = 36)
    private String supplierId;

    /**
     * Human-readable batch identifier shown everywhere in the UI.
     * Auto-generated like "SB-ABCD1234" but can be overridden by the user.
     */
    @Column(name = "batch_number", nullable = false, length = 64)
    private String batchNumber;

    /**
     * Optional user-facing name: "Tuesday Market Run #7", "Farm Fresh Delivery".
     * Helps users identify batches without looking up batch_number.
     */
    @Column(name = "batch_name", length = 255)
    private String batchName;

    /** Which kind of document created this batch. */
    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType;

    /** ID of the document that created this batch (GoodsReceipt.id, RawPurchaseSession.id, etc.). */
    @Column(name = "source_id", nullable = false, length = 36)
    private String sourceId;

    /** Total number of distinct items in this batch. */
    @Column(name = "item_count", nullable = false)
    private int itemCount;

    /** Grand total initial quantity across ALL items (in their respective units). */
    @Column(name = "total_initial_quantity", nullable = false, precision = 18, scale = 4)
    private BigDecimal totalInitialQuantity;

    /** Total quantity remaining across all items. */
    @Column(name = "total_remaining_quantity", nullable = false, precision = 18, scale = 4)
    private BigDecimal totalRemainingQuantity;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "active";

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
```

### DB Migration

**New file:** `backend/src/main/resources/db/migration/V60__supply_batches.sql`

```sql
-- SupplyBatch header entity — groups multiple InventoryBatch rows
-- that arrived together in one delivery / purchase trip.

CREATE TABLE supply_batches (
  id                      CHAR(36)       PRIMARY KEY,
  business_id             CHAR(36)       NOT NULL,
  branch_id               CHAR(36)       NOT NULL,
  supplier_id             CHAR(36)       NULL,
  batch_number            VARCHAR(64)    NOT NULL,
  batch_name              VARCHAR(255)   NULL,
  source_type             VARCHAR(32)    NOT NULL,
  source_id               CHAR(36)       NOT NULL,
  item_count              INT            NOT NULL DEFAULT 0,
  total_initial_quantity  DECIMAL(18,4)  NOT NULL DEFAULT 0,
  total_remaining_quantity DECIMAL(18,4) NOT NULL DEFAULT 0,
  received_at             TIMESTAMP      NOT NULL,
  status                  VARCHAR(16)    NOT NULL DEFAULT 'active',
  version                 BIGINT         NOT NULL DEFAULT 0,
  created_at              TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at              TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_sb_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_sb_branch FOREIGN KEY (branch_id) REFERENCES branches (id),
  CONSTRAINT fk_sb_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id)
);

CREATE INDEX idx_sb_business_status ON supply_batches (business_id, status, received_at);
CREATE INDEX idx_sb_source ON supply_batches (source_type, source_id);
CREATE INDEX idx_sb_supplier ON supply_batches (business_id, supplier_id, status);

-- Add supply_batch_id to inventory_batches
ALTER TABLE inventory_batches
  ADD COLUMN supply_batch_id CHAR(36) NULL AFTER item_id,
  ADD CONSTRAINT fk_ib_supply_batch FOREIGN KEY (supply_batch_id) REFERENCES supply_batches (id);

CREATE INDEX idx_ib_supply_batch ON inventory_batches (supply_batch_id);
```

---

## 5. Changes to Existing Entities

### 5.1 `InventoryBatch` — Add `supplyBatchId`, Remove `batchNumber` (or keep it?)

**Option A (cleaner):** Remove `batchNumber` from `InventoryBatch` since the `SupplyBatch` owns the batch number now. But `InventoryBatch` still needs an identifier for internal tracking. Keep the `batchNumber` field but populate it differently — or replace it with a simpler `line_number`.

**Recommendation:** Keep `batchNumber` on `InventoryBatch` but make it a short line-level label like `"L-01"`, `"L-02"` within the parent `SupplyBatch`. The `SupplyBatch.batchNumber` is the main identifier users see.

**File:** `backend/src/main/java/zelisline/ub/purchasing/domain/InventoryBatch.java`

Add field:
```java
@Column(name = "supply_batch_id", length = 36)
private String supplyBatchId;
```

The `batchNumber` field stays — it becomes the line-level identifier within a supply batch (like `"SB-001-L3"`).

### 5.2 `GoodsReceiptLine` — Already has `inventoryBatchId` — no change needed

The FK to `InventoryBatch` already exists. When GRN lines are created, the `SupplyBatch` is created first, then `InventoryBatch` rows are linked to it.

### 5.3 `RawPurchaseLine` — Already has `inventoryBatchId` — no change needed

Same as above.

### 5.4 `SaleItem` — No change needed

Already has `batch_id` → `InventoryBatch.id`. To trace from a sale back to the Supply Batch:
```java
saleItem.batch.supplyBatch  // → SupplyBatch header with name + number
```

---

## 6. Migration Strategy

### 6.1 Backfill Existing Data

The migration script creates `SupplyBatch` rows from existing `InventoryBatch` rows that share the same `(source_type, source_id)` grouping.

```sql
-- V61__backfill_supply_batches.sql

-- For each unique (source_type, source_id) group in inventory_batches,
-- create a SupplyBatch header.
INSERT INTO supply_batches (id, business_id, branch_id, supplier_id,
                            batch_number, batch_name,
                            source_type, source_id,
                            item_count, total_initial_quantity, total_remaining_quantity,
                            received_at, status, version, created_at, updated_at)
SELECT
    UUID()                                           AS id,
    ib.business_id,
    ib.branch_id,
    ib.supplier_id,
    CONCAT('SB-', SUBSTRING(UUID(), 1, 8))           AS batch_number,
    CONCAT('Batch from ', COALESCE(ib.source_type, 'unknown'), ' (', ib.source_id, ')')
                                                      AS batch_name,
    ib.source_type,
    ib.source_id,
    COUNT(*)                                          AS item_count,
    SUM(ib.initial_quantity)                         AS total_initial_quantity,
    SUM(ib.quantity_remaining)                       AS total_remaining_quantity,
    MIN(ib.received_at)                              AS received_at,
    'active'                                         AS status,
    0                                                 AS version,
    CURRENT_TIMESTAMP                                AS created_at,
    CURRENT_TIMESTAMP                                AS updated_at
FROM inventory_batches ib
WHERE ib.source_type IS NOT NULL
  AND ib.source_id IS NOT NULL
GROUP BY ib.business_id, ib.branch_id, ib.supplier_id, ib.source_type, ib.source_id;

-- Link InventoryBatch rows to their SupplyBatch
UPDATE inventory_batches ib
  JOIN supply_batches sb
    ON sb.source_type = ib.source_type
   AND sb.source_id   = ib.source_id
   AND sb.business_id = ib.business_id
   SET ib.supply_batch_id = sb.id
WHERE ib.supply_batch_id IS NULL;
```

### 6.2 Application-Level Backfill (for rows created during the migration window)

Add a `@PostLoad` / `@PostPersist` hook or use a scheduled job to catch any batches that missed the backfill. For simplicity, the application code should always create `SupplyBatch` rows before creating `InventoryBatch` rows going forward.

---

## 7. New API Endpoints

### 7.1 `GET /api/v1/inventory/supply-batches`

List all supply batches for a business/branch. Supports filtering by status, date range, supplier.

```json
[
  {
    "id": "sb-001",
    "batchNumber": "SB-ABCD1234",
    "batchName": "Tuesday Market Run #7",
    "supplierId": "sup-001",
    "supplierName": "Farm Fresh Eggs Ltd",
    "receivedAt": "2025-06-05T08:30:00Z",
    "status": "active",
    "itemCount": 3,
    "totalInitialQuantity": 1250,
    "totalRemainingQuantity": 688,
    "items": [
      {
        "itemId": "item-001",
        "itemName": "Eggs (Large)",
        "received": 1000,
        "remaining": 680,
        "sold": 300,
        "wasted": 20
      },
      {
        "itemId": "item-002",
        "itemName": "Fresh Milk (1L)",
        "received": 50,
        "remaining": 8,
        "sold": 40,
        "wasted": 2
      },
      {
        "itemId": "item-003",
        "itemName": "Sliced Bread",
        "received": 200,
        "remaining": 0,
        "sold": 200,
        "wasted": 0
      }
    ]
  }
]
```

### 7.2 `GET /api/v1/inventory/supply-batches/{id}`

Full detail of a single supply batch.

### 7.3 `GET /api/v1/inventory/supply-batches/{id}/items`

The items within a supply batch, with full per-item stats (sold, wasted, remaining, profit).

### 7.4 `GET /api/v1/inventory/supply-batches/{id}/movements`

All `StockMovement` rows for items in this supply batch — the complete audit trail.

---

## 8. Query Patterns — Answering Business Questions

### 8.1 "Show me everything in Supply Batch #42"

```sql
SELECT
    sb.batch_number,
    sb.batch_name,
    ib.item_id,
    i.name                          AS item_name,
    ib.initial_quantity              AS received_qty,
    ib.quantity_remaining            AS remaining_qty,
    ib.initial_quantity - ib.quantity_remaining
        - COALESCE(wastage.total_wasted, 0) AS sold_qty,
    COALESCE(wastage.total_wasted, 0)       AS wasted_qty,
    ib.unit_cost,
    ib.supplier_id,
    ib.status
FROM supply_batches sb
JOIN inventory_batches ib ON ib.supply_batch_id = sb.id
JOIN items i ON i.id = ib.item_id
LEFT JOIN (
    SELECT b.item_id,
           ABS(SUM(sm.quantity_delta)) AS total_wasted
    FROM stock_movements sm
    JOIN inventory_batches b ON b.id = sm.batch_id
    WHERE b.supply_batch_id = 'sb-42'
      AND sm.movement_type = 'wastage'
    GROUP BY b.item_id
) wastage ON wastage.item_id = ib.item_id
WHERE sb.id = 'sb-42'
ORDER BY ib.item_id;
```

### 8.2 "Which batches had the highest wastage this month?"

```sql
SELECT
    sb.id,
    sb.batch_number,
    sb.batch_name,
    sb.supplier_id,
    s.name                                  AS supplier_name,
    SUM(ABS(sm.quantity_delta))            AS total_wasted_qty,
    COUNT(DISTINCT sm.item_id)             AS items_affected
FROM stock_movements sm
JOIN inventory_batches ib ON ib.id = sm.batch_id
JOIN supply_batches sb ON sb.id = ib.supply_batch_id
LEFT JOIN suppliers s ON s.id = sb.supplier_id
WHERE sm.movement_type = 'wastage'
  AND sm.created_at >= '2025-06-01'
  AND sm.created_at < '2025-07-01'
  AND sb.business_id = 'business-001'
GROUP BY sb.id, sb.batch_number, sb.supplier_id, s.name
ORDER BY total_wasted_qty DESC;
```

### 8.3 "What items from Batch #42 have sold the most profit?"

```sql
SELECT
    i.name                                 AS item_name,
    SUM(si.quantity)                       AS total_sold,
    SUM(si.profit)                         AS total_profit,
    AVG(si.unit_price)                     AS avg_sell_price,
    ib.unit_cost                           AS cost_price
FROM sale_items si
JOIN inventory_batches ib ON ib.id = si.batch_id
JOIN items i ON i.id = ib.item_id
WHERE ib.supply_batch_id = 'sb-42'
GROUP BY i.name, ib.unit_cost
ORDER BY total_profit DESC;
```

### 8.4 "Trace a sale item back to its supply batch"

```java
// In Java: SaleItem → InventoryBatch → SupplyBatch
SaleItem si = saleItemRepository.findById(saleItemId).orElseThrow();
InventoryBatch ib = inventoryBatchRepository.findById(si.getBatchId()).orElseThrow();
SupplyBatch sb = supplyBatchRepository.findById(ib.getSupplyBatchId()).orElseThrow();

// Now you have:
//   sb.getBatchNumber()  → "SB-ABCD1234"
//   sb.getBatchName()    → "Tuesday Market Run #7"
//   sb.getSupplierId()   → "Farm Fresh Eggs Ltd"
```

---

## 9. Step-by-Step Implementation

### Phase 1 — Foundation (~4–6 hours)

| Step | What | Files |
|---|---|---|
| 1 | Create `SupplyBatch.java` entity in `inventory/domain` | New file |
| 2 | Add `supply_batch_id` column to `InventoryBatch.java` | 1 file |
| 3 | Add `supplyBatchId` field to `InventoryBatch` entity | 1 file |
| 4 | Create V60 migration for `supply_batches` table + FK | 1 SQL file |
| 5 | Create V61 migration for backfilling existing data | 1 SQL file |
| 6 | Create `SupplyBatchRepository.java` | New file |

### Phase 2 — Wire into Purchase Flows (~4–6 hours)

| Step | What | Files |
|---|---|---|
| 7 | **Path A GRN**: In `PathAPurchaseService.executePostGrn()`, create `SupplyBatch` before creating `InventoryBatch` rows. Set `supplyBatchId` on each `InventoryBatch`. | `PathAPurchaseService.java` |
| 8 | **Path B breakdown**: In `PathBPurchaseService.applyLinePost()`, create `SupplyBatch` before creating `InventoryBatch` rows. | `PathBPurchaseService.java` |
| 9 | **Opening balances**: In `InventoryLedgerService.recordOpeningBalance()`, create a `SupplyBatch` for the opening batch. | `InventoryLedgerService.java` |
| 10 | **Stock gains**: In `InventoryLedgerService.recordStockIncrease()`, create a `SupplyBatch`. | `InventoryLedgerService.java` |
| 11 | **Stock transfers**: In `InventoryTransferService`, create `SupplyBatch` for transfer-in batches. | `InventoryTransferService.java` |
| 12 | **Refund batches**: In `SaleRefundService.resolveReturnBatch()`, create `SupplyBatch` for refund return batches. | `SaleRefundService.java` |

### Phase 3 — Reporting / API (~3–4 hours)

| Step | What | Files |
|---|---|---|
| 13 | Create `SupplyBatchController.java` with endpoints | New file |
| 14 | Create DTOs: `SupplyBatchSummaryResponse`, `SupplyBatchDetailResponse`, `SupplyBatchItemResponse` | New files |
| 15 | Build `SupplyBatchReportService.java` — aggregates sold/wasted/remaining per item | New file |
| 16 | Wire the per-batch queries from §8 | Service methods |

### Phase 4 — Cleanup (~2 hours)

| Step | What | Files |
|---|---|---|
| 17 | Update `InventoryBatch` to use `supplyBatchId` in JPA queries | Repository |
| 18 | Add unit tests for `SupplyBatch` creation in each purchase flow | Test files |
| 19 | Add integration test for backfill migration | Test file |
| 20 | Run backfill in production after deployment | Operations |

---

## 10. Frontend Implications

### New UI Sections

**1. Supply Batch List** — a page/section under Inventory showing all batches:
```
┌─────────────────────────────────────────────────────────┐
│ Supply Batches                                          │
├──────────┬──────────┬──────────┬──────────┬──────┬──────┤
│ Batch #  │ Name              │ Supplier │ Items│ Date │
├──────────┼───────────────────┼──────────┼──────┼──────┤
│ SB-1234  │ Tue Market Run #7 │ F Farm   │ 3    │ 5 Jun│
│ SB-1235  │ Mon Wholesale: Ol │ Sunny    │ 12   │ 4 Jun│
│ SB-1236  │ Opening: May 2025 │ —        │ 45   │ 1 Jun│
└──────────┴───────────────────┴──────────┴──────┴──────┘
```

**2. Supply Batch Detail** — showing the batch header + all items with stats:
```
┌────────────────────────────────────────────────────┐
│ Supply Batch #SB-1234                              │
│ Name: Tuesday Market Run #7                        │
│ Supplier: Farm Fresh Eggs Ltd                      │
│ Received: 5 Jun 2025, 08:30                        │
│ Status: Active                                     │
├────────────────────────────────────────────────────┤
│ Items in this batch                                │
├──────────┬─────────┬──────┬──────┬───────┬─────────┤
│ Item     │ Recv'd  │ Sold │Waste │ Left  │ 💰      │
├──────────┼─────────┼──────┼──────┼───────┼─────────┤
│ Eggs     │ 1,000   │ 300  │  20  │ 680   │ 4,590   │
│ Milk (1L)│    50   │  40  │   2  │   8   │ 1,200   │
│ Bread    │   200   │ 200  │   0  │   0   │ 3,000   │
└──────────┴─────────┴──────┴──────┴───────┴─────────┘
```

**3. Each sale item's "View Batch" action** — clicking on a batch number in a sale receipt opens the Supply Batch detail view.

---

## 11. What Happens to the Existing Batch Number / Source Tracking

### Batch Number

| Current | New |
|---|---|
| `InventoryBatch.batchNumber` = `"A-ABCD1234"` (unique per line, unhelpful) | `SupplyBatch.batchNumber` = `"SB-1234"` (shared across lines, human-readable) |
| | `InventoryBatch.batchNumber` = `"L-01"`, `"L-02"` (line-level, optional) |

### Source Tracking

| Current | New |
|---|---|
| `InventoryBatch.source_type` + `source_id` (bulky, repeated in every row) | Moved to `SupplyBatch.source_type` + `source_id` |
| `InventoryBatch.source_type` still exists (denormalized for queries) | Kept in `InventoryBatch` for backward-compatible queries, but now redundant |

### Migration Path

1. New rows: `SupplyBatch` is created first, `InventoryBatch` references it
2. Old rows: Backfill script creates `SupplyBatch` from `(source_type, source_id)` groups
3. After migration: code can read from either place. New code paths prefer `SupplyBatch`.

---

## 12. How This Integrates with the Four Gaps from the Previous Guide

The four gaps from `FOUR_GAPS_IMPLEMENTATION_GUIDE.md` are **orthogonal** to this redesign — they should still be fixed regardless. Here's how they overlap:

| Gap | Impact on Supply Batch Design |
|---|---|
| G1 — Wastage linked to batches | Even more important now. With Supply Batch, wastage should decrement the specific `InventoryBatch` line AND be visible at the Supply Batch level. |
| G2 — Expired batches excluded | The `excludeExpired()` filter works on `InventoryBatch`, which is a line within a Supply Batch. A Supply Batch's items expire independently — one item may be expired while another is fine. No change needed. |
| G3 — Supplier on refunds | The refund `SupplyBatch` should inherit the supplier from the original Supply Batch, not the individual `InventoryBatch` line. Minor tweak: trace `sale_item → inventory_batch → supply_batch → supplier_id`. |
| G4 — Wastage reason enum | No impact. The wastage categories work the same way. |

**Recommended order of implementation:**
1. Fix the four gaps first (they're small, low-risk, and improve correctness immediately)
2. Then implement the Supply Batch redesign (it's larger and builds on the now-correct foundation)

---

## 13. Estimated Effort

| Phase | Hours | Can be parallelized? |
|---|---|---|
| Phase 1 — Foundation | 4–6 | No (sequential steps) |
| Phase 2 — Wire into purchase flows | 4–6 | Yes (Path A, Path B, and standalone can be done in parallel) |
| Phase 3 — Reporting / API | 3–4 | Partial (reporting service and controller are independent of the wire-up) |
| Phase 4 — Cleanup & Tests | 2–3 | Yes |
| **Total** | **13–19 hours** | ~3 working days |

**If you also want to do the four gaps first:** add ~4 hours, bringing the total to **~17–23 hours** (~4 working days).

---

## Summary

| Aspect | Current State | After Redesign |
|---|---|---|
| What is a "batch"? | One item's lot (confusing) | A delivery containing multiple items (intuitive) |
| Batch name | Auto-generated per-line, not useful | User-friendly name + number on the header |
| Can you see all items that arrived together? | Only by querying `source_type` + `source_id` across rows | One query on `SupplyBatch` with joined items |
| Can you trace a sale item to the delivery it came from? | 4-hop join through purchasing docs | `SaleItem → InventoryBatch → SupplyBatch` (direct) |
| Supplier linked to a batch? | At the `InventoryBatch` level (repeated each row) | On the `SupplyBatch` header (one place) |
| Wastage per batch per item? | No header entity to anchor it | Yes — grouped by `supply_batch_id` |
| Impact on existing code | — | Additive. Old code paths still work. Migration backfills. |

**The Supply Batch redesign makes the mental model match how your users actually think — a "batch" is a delivery, not a single item.**
