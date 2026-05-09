# Four Gaps — Step-by-Step Implementation Guide

> **Project**: UB (Palmart) — Spring Boot 3  
> **Date**: 2025-06-06  
> **Expected effort**: ~4–6 hours total (all four fixes)  
> **Risk**: Low — most changes are additive, none touch the sale journal or locking strategy

---

## Guide Structure

Each gap follows the same pattern:
1. **What's broken** — the specific problem, with concrete example
2. **Files to touch** — exact paths
3. **Step-by-step changes** — copy-paste-ready code diffs
4. **Verification** — how to confirm the fix worked

---

---

## GAP 1 — Link Wastage to Batches

### What's Broken

```
Current:  SUM(inventory_batches.quantity_remaining) ≠ Item.current_stock

Because:  Wastage creates a StockMovement with batch_id = NULL
          and skips decrementing any batch's quantity_remaining.
          
Result:   You can't trust batch-level inventory counts.
          Stock-take reconciliation is a guessing game.
```

### Files to Touch

| File | What Changes |
|---|---|
| `backend/src/main/java/zelisline/ub/inventory/api/dto/PostStandaloneWastageRequest.java` | Add optional `batchId` field |
| `backend/src/main/java/zelisline/ub/inventory/application/InventoryLedgerService.java` | Rewrite `recordStandaloneWastage()` |
| `backend/src/main/java/zelisline/ub/purchasing/application/PathBPurchaseService.java` | Fix `applyLinePost()` wastage path |

---

### Step 1 — Add `batchId` to the wastage request DTO

**File:** `backend/src/main/java/zelisline/ub/inventory/api/dto/PostStandaloneWastageRequest.java`

**Current:**
```java
package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PostStandaloneWastageRequest(
        @NotBlank String branchId,
        @NotBlank String itemId,
        @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal quantity,
        @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal unitCost,
        @Size(max = 255) String reason
) {
}
```

**Replace with:**
```java
package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PostStandaloneWastageRequest(
        @NotBlank String branchId,
        @NotBlank String itemId,
        @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal quantity,
        @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal unitCost,
        @Size(max = 255) String reason,
        // NEW — if provided, deplete this specific batch.
        // If null, the system auto-picks the most eligible batch (FEFO → FIFO).
        String batchId
) {
}
```

---

### Step 2 — Rewrite `recordStandaloneWastage()` in InventoryLedgerService

**File:** `backend/src/main/java/zelisline/ub/inventory/application/InventoryLedgerService.java`

**Current method (lines ~155–180):**
```java
@Transactional
public InventoryMutationResponse recordStandaloneWastage(
        String businessId,
        PostStandaloneWastageRequest req,
        String userId
) {
    requireBranch(businessId, req.branchId());
    Item item = requireStockedItem(businessId, req.itemId());
    String opId = UUID.randomUUID().toString();
    StockMovement mv = persistMovement(
            businessId,
            req.branchId(),
            item.getId(),
            null,                          // ← batch_id is NULL
            PurchasingConstants.MOVEMENT_WASTAGE,
            opId,
            req.quantity().negate(),
            req.unitCost(),
            req.reason(),
            userId
    );
    applyStockDelta(item, req.quantity().negate());
    BigDecimal value = extensionMoney(req.quantity(), req.unitCost());
    String jeId = saveJournal(
            businessId,
            InventoryConstants.JOURNAL_STANDALONE_WASTAGE,
            opId,
            "Inventory wastage",
            value,
            false
    );
    return new InventoryMutationResponse(jeId, mv.getId(), null);
}
```

**Replace with:**
```java
@Transactional
public InventoryMutationResponse recordStandaloneWastage(
        String businessId,
        PostStandaloneWastageRequest req,
        String userId
) {
    requireBranch(businessId, req.branchId());
    Item item = requireStockedItem(businessId, req.itemId());
    String opId = UUID.randomUUID().toString();

    // ── Resolve the target batch ──────────────────────────────────
    InventoryBatch batch = resolveWastageBatch(businessId, req, item);

    // ── Decrement the batch ───────────────────────────────────────
    BigDecimal qty = req.quantity().setScale(QTY_SCALE, RoundingMode.HALF_UP);
    if (batch.getQuantityRemaining().compareTo(qty) < 0) {
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Wastage quantity (" + qty + ") exceeds batch remaining ("
                        + batch.getQuantityRemaining() + ")"
        );
    }
    batch.setQuantityRemaining(batch.getQuantityRemaining().subtract(qty));
    inventoryBatchRepository.save(batch);

    // ── Record the movement (now WITH batch_id) ───────────────────
    StockMovement mv = persistMovement(
            businessId,
            req.branchId(),
            item.getId(),
            batch.getId(),                     // ← NOW LINKED
            PurchasingConstants.MOVEMENT_WASTAGE,
            opId,
            qty.negate(),
            batch.getUnitCost(),               // ← use actual batch cost
            req.reason(),
            userId
    );
    applyStockDelta(item, qty.negate());

    BigDecimal value = extensionMoney(qty, batch.getUnitCost());
    String jeId = saveJournal(
            businessId,
            InventoryConstants.JOURNAL_STANDALONE_WASTAGE,
            opId,
            "Inventory wastage — batch " + batch.getBatchNumber(),
            value,
            false
    );
    return new InventoryMutationResponse(jeId, mv.getId(), batch.getId());
}

/**
 * Resolves which batch to deplete for wastage.
 * If the caller specifies a batchId, use that (validate it).
 * Otherwise, auto-pick the most eligible batch using FEFO → FIFO.
 */
private InventoryBatch resolveWastageBatch(
        String businessId,
        PostStandaloneWastageRequest req,
        Item item
) {
    if (req.batchId() != null && !req.batchId().isBlank()) {
        // ── Caller picked a specific batch ────────────────────────
        InventoryBatch b = inventoryBatchRepository
                .findByIdAndBusinessId(req.batchId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Batch not found"));
        if (!InventoryConstants.BATCH_STATUS_ACTIVE.equals(b.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Batch is not active");
        }
        if (!b.getBranchId().equals(req.branchId())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Batch does not belong to this branch");
        }
        if (!b.getItemId().equals(item.getId())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Batch item does not match");
        }
        return b;
    }

    // ── Auto-pick: load active batches, sort FEFO → FIFO, take first ─
    List<InventoryBatch> candidates = inventoryBatchRepository
            .findByBusinessIdAndItemIdAndBranchIdAndStatusAndQuantityRemainingGreaterThanOrderByIdAsc(
                    businessId,
                    item.getId(),
                    req.branchId(),
                    InventoryConstants.BATCH_STATUS_ACTIVE,
                    BigDecimal.ZERO
            );
    if (candidates.isEmpty()) {
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "No active batches with remaining quantity");
    }

    // Reuse the existing sort logic from BatchAllocationPlanner
    List<InventoryBatch> working = new ArrayList<>(candidates);
    BatchAllocationPlanner.sortBatchesForPick(
            working,
            item,
            CostMethod.FIFO   // wastage: oldest first (FEFO if expiry exists)
    );
    return working.getFirst();
}
```

**You also need to add these imports** at the top of `InventoryLedgerService.java`:

```java
import java.util.ArrayList;
import java.util.List;
import zelisline.ub.inventory.CostMethod;
import zelisline.ub.inventory.application.BatchAllocationPlanner;
```

---

### Step 3 — Fix Path B wastage in PathBPurchaseService

**File:** `backend/src/main/java/zelisline/ub/purchasing/application/PathBPurchaseService.java`

**Find this block** in `applyLinePost()` (approximately lines 377–388):

```java
if (p.wastageQty().signum() > 0) {
    BigDecimal wUnit = s.wastageMoney().divide(p.wastageQty(), UNIT_SCALE, RoundingMode.HALF_UP);
    StockMovement wm = new StockMovement();
    wm.setBusinessId(businessId);
    wm.setBranchId(session.getBranchId());
    wm.setItemId(p.itemId());
    wm.setBatchId(null);                                         // ← NULL
    wm.setMovementType(PurchasingConstants.MOVEMENT_WASTAGE);
    wm.setReferenceType(PurchasingConstants.STOCK_REF_RAW_LINE);
    wm.setReferenceId(line.getId());
    wm.setQuantityDelta(p.wastageQty().negate());
    wm.setUnitCost(wUnit);
    wm.setReason("Path B wastage");
    stockMovementRepository.save(wm);
}
```

**Replace with:**
```java
if (p.wastageQty().signum() > 0) {
    BigDecimal wUnit = s.wastageMoney().divide(p.wastageQty(), UNIT_SCALE, RoundingMode.HALF_UP);

    // ── Create a wastage batch to hold the "wasted" stock record ──
    InventoryBatch wasteBatch = new InventoryBatch();
    wasteBatch.setBusinessId(businessId);
    wasteBatch.setBranchId(session.getBranchId());
    wasteBatch.setItemId(p.itemId());
    wasteBatch.setSupplierId(session.getSupplierId());
    wasteBatch.setBatchNumber("W-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    wasteBatch.setSourceType("path_b_wastage");
    wasteBatch.setSourceId(line.getId());
    wasteBatch.setInitialQuantity(p.wastageQty());
    wasteBatch.setQuantityRemaining(BigDecimal.ZERO);  // fully depleted
    wasteBatch.setUnitCost(wUnit);
    wasteBatch.setReceivedAt(session.getReceivedAt());
    wasteBatch.setStatus("depleted");                  // not pickable
    inventoryBatchRepository.save(wasteBatch);

    // ── Record movement linked to the wastage batch ───────────────
    StockMovement wm = new StockMovement();
    wm.setBusinessId(businessId);
    wm.setBranchId(session.getBranchId());
    wm.setItemId(p.itemId());
    wm.setBatchId(wasteBatch.getId());                           // ← NOW LINKED
    wm.setMovementType(PurchasingConstants.MOVEMENT_WASTAGE);
    wm.setReferenceType(PurchasingConstants.STOCK_REF_RAW_LINE);
    wm.setReferenceId(line.getId());
    wm.setQuantityDelta(p.wastageQty().negate());
    wm.setUnitCost(wUnit);
    wm.setReason("Path B wastage");
    stockMovementRepository.save(wm);
}
```

---

### Step 4 — Verification

**Manual test:**
1. Create an item with stock in two batches (BATCH-A: 50, BATCH-B: 30)
2. POST wastage for 5 units without `batchId` → should auto-pick from oldest batch
3. Verify: `BATCH-A.quantity_remaining` = 45, `Item.current_stock` = 75
4. Verify: `StockMovement` has `batch_id` = BATCH-A's UUID
5. Confirm: `SUM(batch.quantity_remaining)` == `Item.current_stock`

**SQL sanity check:**
```sql
SELECT item_id, SUM(quantity_remaining) AS batch_sum
FROM inventory_batches
WHERE business_id = '<business-id>' AND status = 'active'
GROUP BY item_id;

-- Compare against:
SELECT id, current_stock FROM items WHERE business_id = '<business-id>';
```

---

---

## GAP 2 — Exclude Expired Batches from Sale Picks

### What's Broken

```
Current:  FEFO comparator uses: expiryDate != null ? expiryDate : LocalDate.MAX

Problem:  A batch with expiry_date = 2025-01-15 (5 months ago) sorts FIRST
          in FEFO order. The system will happily pick it for a sale today.
          
          A cashier could sell expired milk because the system served it up.
```

### Files to Touch

| File | What Changes |
|---|---|
| `backend/src/main/java/zelisline/ub/inventory/application/BatchAllocationPlanner.java` | Filter + comparator changes |
| `backend/src/main/java/zelisline/ub/inventory/application/InventoryBatchPickerService.java` | Filter in `loadActiveBatchesReadOnly()` |
| `backend/src/main/java/zelisline/ub/purchasing/repository/InventoryBatchRepository.java` | (Optional) New query method |

---

### Step 1 — Add expiry filter to `BatchAllocationPlanner`

**File:** `backend/src/main/java/zelisline/ub/inventory/application/BatchAllocationPlanner.java`

**Add this constant and method:**

```java
private static final LocalDate TODAY = LocalDate.now();

/**
 * Filters out batches that have passed their expiry date.
 * Call this BEFORE sorting/allocating.
 */
public static List<InventoryBatch> excludeExpired(List<InventoryBatch> batches) {
    return batches.stream()
            .filter(b -> b.getExpiryDate() == null || !b.getExpiryDate().isBefore(TODAY))
            .toList();
}
```

**Update the `fefoComparator()` method** to handle expired dates:

**Current:**
```java
private static Comparator<InventoryBatch> fefoComparator() {
    return Comparator
            .comparing((InventoryBatch b) -> b.getExpiryDate() != null ? b.getExpiryDate() : LocalDate.MAX)
            .thenComparing(InventoryBatch::getReceivedAt);
}
```

**Replace with:**
```java
private static Comparator<InventoryBatch> fefoComparator() {
    return Comparator
            .comparing((InventoryBatch b) -> {
                // Expired batches sort to the END (they should already be filtered,
                // but this is defense-in-depth)
                if (b.getExpiryDate() != null && b.getExpiryDate().isBefore(TODAY)) {
                    return LocalDate.MAX;
                }
                return b.getExpiryDate() != null ? b.getExpiryDate() : LocalDate.MAX;
            })
            .thenComparing(InventoryBatch::getReceivedAt);
}
```

---

### Step 2 — Filter in the picker service

**File:** `backend/src/main/java/zelisline/ub/inventory/application/InventoryBatchPickerService.java`

**In `pickAndApplyPhysicalDecrement()`** (all overloads), after loading batches and before sorting, insert the filter:

Find these lines:
```java
List<InventoryBatch> working = new ArrayList<>(locked);
BatchAllocationPlanner.sortBatchesForPick(
        working,
        item,
        costMethodForTenant(businessId)
);
```

Replace with:
```java
List<InventoryBatch> working = new ArrayList<>(locked);
// ── Exclude expired batches BEFORE sorting ────────────────────────
working = new ArrayList<>(BatchAllocationPlanner.excludeExpired(working));
if (working.isEmpty()) {
    throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "No non-expired stock available for this item"
    );
}
BatchAllocationPlanner.sortBatchesForPick(
        working,
        item,
        costMethodForTenant(businessId)
);
```

Do the same in `previewAllocation()` — find:
```java
List<InventoryBatch> working = new ArrayList<>(batches);
BatchAllocationPlanner.sortBatchesForPick(
        working,
        item,
        costMethodForTenant(businessId)
);
```

Replace with:
```java
List<InventoryBatch> working = new ArrayList<>(batches);
working = new ArrayList<>(BatchAllocationPlanner.excludeExpired(working));
if (working.isEmpty()) {
    throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "No non-expired stock available for this item"
    );
}
BatchAllocationPlanner.sortBatchesForPick(
        working,
        item,
        costMethodForTenant(businessId)
);
```

---

### Step 3 (Optional but Recommended) — Add a DB-level filter

If you want to push the expiry filter to the database (more performant at scale), add this query method:

**File:** `backend/src/main/java/zelisline/ub/purchasing/repository/InventoryBatchRepository.java`

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("""
        select b from InventoryBatch b
         where b.businessId = :businessId
           and b.itemId = :itemId
           and b.branchId = :branchId
           and b.status = :status
           and b.quantityRemaining > :minRemaining
           and (b.expiryDate is null or b.expiryDate >= :today)
         order by b.id asc
        """)
List<InventoryBatch> lockActiveNonExpiredBatchesForPick(
        @Param("businessId") String businessId,
        @Param("itemId") String itemId,
        @Param("branchId") String branchId,
        @Param("status") String status,
        @Param("minRemaining") BigDecimal minRemaining,
        @Param("today") LocalDate today
);
```

Then update `pickAndApplyPhysicalDecrement()` to call this instead of `lockActiveBatchesForPick()`, passing `LocalDate.now()`. This makes the Java-level `excludeExpired()` filter a safety net rather than the primary defense.

---

### Step 4 — Verification

**Manual test:**
1. Create two active batches of the same item:
   - BATCH-A: expiry = yesterday, qty = 10
   - BATCH-B: expiry = next month, qty = 10
2. Attempt a sale of 5 units
3. Verify: only BATCH-B is picked (BATCH-A is silently excluded)
4. Verify: if you try to sell 15 units, you get "Insufficient stock" (because BATCH-A is excluded, only 10 is available)
5. Verify: `previewAllocation` also excludes BATCH-A

---

---

## GAP 3 — Copy supplier_id on Refund Batches

### What's Broken

```
Current:  SaleRefundService.resolveReturnBatch() creates a new batch with:
              b.setSupplierId(null);   ← supplier link is LOST

Result:   Refunded stock appears under "Unknown Supplier" in reports.
          Supplier spend/return reports are inaccurate.
```

### Files to Touch

| File | What Changes |
|---|---|
| `backend/src/main/java/zelisline/ub/sales/application/SaleRefundService.java` | Two lines in `resolveReturnBatch()` |

---

### Step 1 — Trace the supplier from the original sale item's batch

**File:** `backend/src/main/java/zelisline/ub/sales/application/SaleRefundService.java`

**Find the `resolveReturnBatch()` method** (approximately lines 280–305). Look for this block:

```java
private StockTarget resolveReturnBatch(
        String businessId,
        Sale sale,
        String refundId,
        SaleItem si,
        BigDecimal quantity
) {
    InventoryBatch orig = inventoryBatchRepository
            .findByIdAndBusinessIdForUpdate(si.getBatchId(), businessId)
            .orElse(null);
    if (orig != null
            && orig.getBranchId().equals(sale.getBranchId())
            && InventoryConstants.BATCH_STATUS_ACTIVE.equals(orig.getStatus())) {
        return new StockTarget(orig);
    }
    InventoryBatch b = new InventoryBatch();
    b.setBusinessId(businessId);
    b.setBranchId(sale.getBranchId());
    b.setItemId(si.getItemId());
    b.setSupplierId(null);                                    // ← PROBLEM
    b.setBatchNumber("RR-" + refundId.replace("-", "").substring(0, 12));
    b.setSourceType(InventoryConstants.BATCH_SOURCE_REFUND_RETURN);
    b.setSourceId(refundId);
    b.setInitialQuantity(quantity);
    b.setQuantityRemaining(quantity);
    b.setUnitCost(si.getUnitCost().setScale(QTY_SCALE, RoundingMode.HALF_UP));
    b.setReceivedAt(Instant.now());
    b.setStatus(InventoryConstants.BATCH_STATUS_ACTIVE);
    inventoryBatchRepository.save(b);
    return new StockTarget(b);
}
```

**Replace the `b.setSupplierId(null)` line and add the supplier resolution:**

```java
private StockTarget resolveReturnBatch(
        String businessId,
        Sale sale,
        String refundId,
        SaleItem si,
        BigDecimal quantity
) {
    InventoryBatch orig = inventoryBatchRepository
            .findByIdAndBusinessIdForUpdate(si.getBatchId(), businessId)
            .orElse(null);
    if (orig != null
            && orig.getBranchId().equals(sale.getBranchId())
            && InventoryConstants.BATCH_STATUS_ACTIVE.equals(orig.getStatus())) {
        return new StockTarget(orig);
    }
    // ── Resolve supplier from the ORIGINAL sale item's batch ──────
    String supplierId = resolveSupplierFromOriginalBatch(businessId, si);

    InventoryBatch b = new InventoryBatch();
    b.setBusinessId(businessId);
    b.setBranchId(sale.getBranchId());
    b.setItemId(si.getItemId());
    b.setSupplierId(supplierId);                               // ← FIXED
    b.setBatchNumber("RR-" + refundId.replace("-", "").substring(0, 12));
    b.setSourceType(InventoryConstants.BATCH_SOURCE_REFUND_RETURN);
    b.setSourceId(refundId);
    b.setInitialQuantity(quantity);
    b.setQuantityRemaining(quantity);
    b.setUnitCost(si.getUnitCost().setScale(QTY_SCALE, RoundingMode.HALF_UP));
    b.setReceivedAt(Instant.now());
    b.setStatus(InventoryConstants.BATCH_STATUS_ACTIVE);
    inventoryBatchRepository.save(b);
    return new StockTarget(b);
}

/**
 * Traces the supplier from the original batch that the sale item was sold from.
 * Reads the batch WITHOUT locking (read-only lookup).
 */
private String resolveSupplierFromOriginalBatch(String businessId, SaleItem si) {
    return inventoryBatchRepository
            .findByIdAndBusinessId(si.getBatchId(), businessId)
            .map(InventoryBatch::getSupplierId)
            .orElse(null);
}
```

**Add the import** at the top of `SaleRefundService.java` if not already present:

```java
import zelisline.ub.purchasing.domain.InventoryBatch;
```
(This should already be imported — verify.)

---

### Step 2 — Verification

**Manual test:**
1. Create a Path B purchase from supplier "Farm Fresh Eggs Ltd"
2. Sell 3 eggs (creates `SaleItem` with `batch_id` pointing to the purchase batch)
3. Refund 2 eggs
4. Query the new refund batch:
   ```sql
   SELECT batch_number, supplier_id, source_type
   FROM inventory_batches
   WHERE source_type = 'refund_return'
   ORDER BY created_at DESC LIMIT 1;
   ```
5. Verify: `supplier_id` matches "Farm Fresh Eggs Ltd", not NULL

---

---

## GAP 4 — Enum for Wastage Reasons

### What's Broken

```
Current:  Wastage reason is a free-text VARCHAR(255).
          "spoilage", "Spoilage", "SPOILED", "rotten" all mean the same thing.
          
Result:   Can't run reliable reports on wastage categories.
          Can't answer: "How much did we lose to theft vs spoilage last quarter?"
```

### Files to Touch

| File | What Changes |
|---|---|
| `backend/src/main/java/zelisline/ub/inventory/WastageReason.java` | **NEW** — enum |
| `backend/src/main/java/zelisline/ub/inventory/api/dto/PostStandaloneWastageRequest.java` | Add `wastageReason` field |
| `backend/src/main/java/zelisline/ub/inventory/application/InventoryLedgerService.java` | Use enum name in movement |
| `backend/src/main/java/zelisline/ub/purchasing/application/PathBPurchaseService.java` | Use enum for Path B wastage |
| `backend/src/main/resources/db/migration/V60__wastage_reason_column.sql` | **NEW** — DB migration |

---

### Step 1 — Create the enum

**New file:** `backend/src/main/java/zelisline/ub/inventory/WastageReason.java`

```java
package zelisline.ub.inventory;

/**
 * Standardised categories for inventory wastage / shrinkage.
 * Maps to {@code stock_movements.reason} values.
 *
 * <p>These replace free-text reasons so reports can group wastage
 * by category (spoilage vs theft vs breakage etc.).</p>
 */
public enum WastageReason {

    /** Natural decay — produce, dairy, baked goods past usable condition. */
    SPOILAGE,

    /** Physical damage — broken eggs, crushed packaging, dropped items. */
    BREAKAGE,

    /** Confirmed or suspected theft / pilferage. */
    THEFT,

    /** Items taken for quality testing, demo, or sampling. */
    SAMPLE,

    /** Owner / staff personal consumption written off. */
    PERSONAL_USE,

    /** Batch exceeded its expiry date and was written off. */
    EXPIRED,

    /** Discrepancy found during stock-take (counting error). */
    COUNTING_ERROR,

    /** Anything that doesn't fit the categories above. */
    OTHER;

    /**
     * Returns the enum constant matching {@code input}, case-insensitively,
     * trimming whitespace, or {@code OTHER} if no match.
     */
    public static WastageReason fromString(String input) {
        if (input == null || input.isBlank()) {
            return OTHER;
        }
        String cleaned = input.strip().toUpperCase().replace(' ', '_');
        for (WastageReason r : values()) {
            if (r.name().equals(cleaned)) {
                return r;
            }
        }
        return OTHER;
    }
}
```

---

### Step 2 — Add `wastageReason` to the request DTO

**File:** `backend/src/main/java/zelisline/ub/inventory/api/dto/PostStandaloneWastageRequest.java`

**After Gap 1 changes, the record looks like:**
```java
public record PostStandaloneWastageRequest(
        @NotBlank String branchId,
        @NotBlank String itemId,
        @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal quantity,
        @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal unitCost,
        @Size(max = 255) String reason,
        String batchId
) {
}
```

**Replace with (add `wastageReason`):**
```java
public record PostStandaloneWastageRequest(
        @NotBlank String branchId,
        @NotBlank String itemId,
        @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal quantity,
        @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal unitCost,
        @Size(max = 255) String reason,         // free-text notes (kept for backward compat)
        String batchId,
        String wastageReason                    // NEW — enum value: SPOILAGE, BREAKAGE, THEFT, etc.
) {
}
```

---

### Step 3 — Use the enum in `InventoryLedgerService`

**File:** `backend/src/main/java/zelisline/ub/inventory/application/InventoryLedgerService.java`

**In `recordStandaloneWastage()`**, find the `persistMovement` call and update the `reason` parameter:

Find:
```java
StockMovement mv = persistMovement(
        businessId,
        req.branchId(),
        item.getId(),
        batch.getId(),
        PurchasingConstants.MOVEMENT_WASTAGE,
        opId,
        qty.negate(),
        batch.getUnitCost(),
        req.reason(),
        userId
);
```

Replace with:
```java
// ── Resolve enum reason ──────────────────────────────────────────
WastageReason cat = WastageReason.fromString(req.wastageReason());
String movementReason;
if (req.reason() != null && !req.reason().isBlank()) {
    movementReason = cat.name() + " — " + req.reason();  // "BREAKAGE — Dropped crate"
} else {
    movementReason = cat.name();                          // "BREAKAGE"
}

StockMovement mv = persistMovement(
        businessId,
        req.branchId(),
        item.getId(),
        batch.getId(),
        PurchasingConstants.MOVEMENT_WASTAGE,
        opId,
        qty.negate(),
        batch.getUnitCost(),
        movementReason,              // ← NOW standardised
        userId
);
```

Add the import:
```java
import zelisline.ub.inventory.WastageReason;
```

---

### Step 4 — Update Path B wastage

**File:** `backend/src/main/java/zelisline/ub/purchasing/application/PathBPurchaseService.java`

**In `applyLinePost()`**, find the wastage `StockMovement` we already fixed in Gap 1. Update the reason:

Find:
```java
wm.setReason("Path B wastage");
```

Replace with:
```java
wm.setReason(WastageReason.SPOILAGE.name() + " — Path B breakdown");
```

Add the import:
```java
import zelisline.ub.inventory.WastageReason;
```

---

### Step 5 (Optional) — DB Migration for backward-compatible reporting

If you want to query wastage by category efficiently without parsing the `reason` text, add a column:

**New file:** `backend/src/main/resources/db/migration/V60__wastage_reason_column.sql`

```sql
-- Add a dedicated wastage_reason column for enum-based reporting.
-- Existing reason text is preserved; new rows populate both.

ALTER TABLE stock_movements
  ADD COLUMN wastage_reason VARCHAR(32) NULL
  AFTER reason;

-- Backfill existing wastage rows with a best-effort match
UPDATE stock_movements
   SET wastage_reason = 'OTHER'
 WHERE movement_type = 'wastage'
   AND wastage_reason IS NULL;

CREATE INDEX idx_sm_wastage_reason
    ON stock_movements (business_id, wastage_reason, created_at)
 WHERE movement_type = 'wastage';
```

If you add this column, also update `StockMovement.java`:

```java
@Column(name = "wastage_reason", length = 32)
private String wastageReason;
```

And set it in the wastage-creation code paths:
```java
wm.setWastageReason(cat.name());
```

---

### Step 6 — Verification

**Manual test:**
1. POST a standalone wastage with `"wastageReason": "BREAKAGE"` and `"reason": "Dropped crate of eggs"`
2. Verify `stock_movements.reason` = `"BREAKAGE — Dropped crate of eggs"`
3. POST wastage with `"wastageReason": "THEFT"` and no free-text reason
4. Verify `stock_movements.reason` = `"THEFT"`
5. POST wastage with `"wastageReason": "garbage_value"` 
6. Verify it falls back to `"OTHER"`
7. Run Path B with wastage → verify `reason` starts with `"SPOILAGE"`

**Reporting query** (after migration):
```sql
SELECT wastage_reason, COUNT(*) AS events, SUM(ABS(quantity_delta)) AS total_qty
FROM stock_movements
WHERE business_id = '<id>'
  AND movement_type = 'wastage'
  AND created_at >= '2025-01-01'
GROUP BY wastage_reason
ORDER BY total_qty DESC;
```

---

---

## Complete File Change Summary

| File | Gaps Touched | Nature of Change |
|---|---|---|
| `inventory/api/dto/PostStandaloneWastageRequest.java` | 1, 4 | Add `batchId`, `wastageReason` fields |
| `inventory/application/InventoryLedgerService.java` | 1, 4 | Rewrite `recordStandaloneWastage()`, add `resolveWastageBatch()` |
| `inventory/application/BatchAllocationPlanner.java` | 2 | Add `excludeExpired()`, harden `fefoComparator()` |
| `inventory/application/InventoryBatchPickerService.java` | 2 | Filter expired in `pickAndApplyPhysicalDecrement()` and `previewAllocation()` |
| `sales/application/SaleRefundService.java` | 3 | Add `resolveSupplierFromOriginalBatch()`, fix `setSupplierId()` |
| `purchasing/application/PathBPurchaseService.java` | 1, 4 | Create wastage batch, standardise reason |
| `inventory/WastageReason.java` | 4 | **NEW FILE** — enum |
| `purchasing/repository/InventoryBatchRepository.java` | 2 | (Optional) Add `lockActiveNonExpiredBatchesForPick()` |
| `db/migration/V60__wastage_reason_column.sql` | 4 | (Optional) New column + index + backfill |
| `purchasing/domain/StockMovement.java` | 4 | (Optional) New `wastageReason` field |

---

## Execution Order

1. **Gap 4 first** (enum + DTO changes) — because Gaps 1 and 3 will reference the new types
2. **Gap 1** (wastage → batch linking) — depends on the DTO from Gap 4
3. **Gap 2** (expiry filtering) — independent, can be done in parallel with Gaps 3/4
4. **Gap 3** (refund supplier link) — one method change, independent

All four are backward-compatible at the API level (new fields are optional, existing fields are preserved). No breaking changes to the frontend unless you choose to update the wastage form to expose `wastageReason` as a dropdown.
