# Batch Tracking & Clearing — Creative UI Specification

> **Project**: UB (Palmart) Frontend — Next.js / React / shadcn  
> **Date**: 2025-06-06  
> **Purpose**: A visual, interactive batch tracking experience with auto-clearing and a clearing wizard

---

## Design Philosophy

**A batch should feel like a living thing.** You receive it. You watch it deplete in real-time. When it's done, you close it with ceremony — accounting for every last unit. The UI should make this satisfying, not tedious.

**Guiding principles:**
- **See the state at a glance** — colour-coded progress, sparklines, visual indicators
- **Clear with intent** — the clearing wizard turns a chore into a meaningful close-out ritual
- **Every unit accounted for** — nothing disappears. Everything is sold, wasted, or written off with a reason
- **Celebrate completion** — a closed batch is a job well done

---

## 1. Supply Batch List — Status at a Glance

### Visual Status Indicators

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  Supply Batches                                                    [Filters] │
├──────────┬─────────────┬───────────┬──────────┬───────┬──────┬──────────────┤
│ Batch    │ Items        │ Progress  │ Waste    │ Value │ Age  │ Actions      │
├──────────┼─────────────┼───────────┼──────────┼───────┼──────┼──────────────┤
│ SB-1234  │ Eggs, Milk, │ ██████░░░ │    22    │ 4.5k  │ 3d   │ [Clear]      │
│ Tue Run  │ Bread       │  72%      │  (2%)    │       │      │              │
├──────────┼─────────────┼───────────┼──────────┼───────┼──────┼──────────────┤
│ SB-1240  │ Tomatoes,   │ ██████████│    45    │ 12k   │ 7d   │ [View]       │
│ Mon Whsl │ Onions, ... │  100% ✅  │  (3%)    │       │      │              │
├──────────┼─────────────┼───────────┼──────────┼───────┼──────┼──────────────┤
│ SB-1250  │ Milk, Yogurt│ ░░░░░░░░░░│    —     │  -    │ 12d  │ [Clear] 🔴   │
│ Fri Del  │             │   0%      │          │       │      │ (expiring)   │
├──────────┼─────────────┼───────────┼──────────┼───────┼──────┼──────────────┤
│ SB-1260  │ Eggs        │ ██████████│     0    │ 2.1k  │ 30d  │ ✅ Closed     │
│ Op Bal   │             │  100% 🔒  │          │       │      │              │
└──────────┴─────────────┴───────────┴──────────┴───────┴──────┴──────────────┘
```

### Progress Bar Design

A simple horizontal bar with three colour zones:

```
████████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░
◀── SOLD ──▶◀─── Remaining ──────────▶
  72%              28%
```

- **Green** = sold portion
- **Orange** = remaining (active sales)
- **Red** = expired (if any)
- **Grey** = written off (if partially cleared)

### Age Indicator

```
3d     → green (fresh, < 7 days)
7d     → amber (aging, 7–14 days)
12d    → red (old, > 14 days — especially for perishables)
```

### Row-Level Actions

| Batch State | Show button | Behaviour |
|---|---|---|
| Active + some items at zero | "Clear" | Opens wizard |
| Active + all zero | "Complete" | Auto-close (no wizard needed) |
| Expiring soon (red age) | "Clear 🔴" | Urgent — opens wizard |
| Closed | "✅ Closed" | Disabled badge, shows close info |
| Soldout | "Close" | One-click finalise |

---

## 2. Supply Batch Detail — The Batch Dashboard

### Layout

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  ← Supply Batches                                                            │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  SB-1234  ·  Tuesday Market Run #7                       [Close batch] │  │
│  │  Farm Fresh Eggs Ltd  ·  5 Jun 2025  ·  Status: ● Active              │  │
│  │                                                                        │  │
│  │  ┌────────────────────────────────────────────────────────────────┐   │  │
│  │  │  Batch Health                                                 │   │  │
│  │  │  ┌────────────┬────────────┬────────────┬────────────┐       │   │  │
│  │  │  │ 📦 3 Items │ 📈 72%     │ 📉 2% Waste │ ⏱ 3 days  │       │   │  │
│  │  │  │            │ sold       │            │ old        │       │   │  │
│  │  │  └────────────┴────────────┴────────────┴────────────┘       │   │  │
│  │  │                                                              │   │  │
│  │  │  ┌─ Batch Progress ───────────────────────────────────────┐  │   │  │
│  │  │  │                                                        │  │   │  │
│  │  │  │  SOLD  ██████████████████████████░░░░░░░░░░░  72%     │  │   │  │
│  │  │  │       ◀──────── 900 of 1,250 ────────▶                │  │   │  │
│  │  │  │                                                        │  │   │  │
│  │  │  │  📉 Waste: 22 units (2%)     💰 Profit: +5,100 KES   │  │   │  │
│  │  │  └────────────────────────────────────────────────────────┘  │   │  │
│  │  └────────────────────────────────────────────────────────────────┘   │  │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  Items in this Batch                                                         │
│  ┌──────────┬───────┬────────┬────────┬───────┬───────┬────────┬──────────┐ │
│  │ Item     │ Received │ Sold│ Waste  │ Left  │ Cost  │ Profit │ Status   │ │
│  ├──────────┼───────┼────────┼────────┼───────┼───────┼────────┼──────────┤ │
│  │ Eggs     │ 1,000 │ 300    │ ██ 20  │ 680   │ 4,590 │ +3,000 │ ● Active │ │
│  │          │       │        │        │ ───▶  │       │        │          │ │
│  ├──────────┼───────┼────────┼────────┼───────┼───────┼────────┼──────────┤ │
│  │ Milk     │   50  │  40    │ █ 2    │   8   │  734  │  +600  │ ⚠️ Low   │ │
│  │          │       │        │        │ ───▶  │       │        │          │ │
│  ├──────────┼───────┼────────┼────────┼───────┼───────┼────────┼──────────┤ │
│  │ Bread    │  200  │ 200    │  0     │   0   │ 1,500 │ +1,500 │ ✅ Done  │ │
│  ├──────────┼───────┼────────┼────────┼───────┼───────┼────────┼──────────┤ │
│  │ TOTAL    │ 1,250 │ 540    │  22    │  688  │ 6,824 │ +5,100 │          │ │
│  └──────────┴───────┴────────┴────────┴───────┴───────┴────────┴──────────┘ │
│                                                                              │
│  ▼ Movement Timeline (22 entries)  [Show all]                                │
│                                                                              │
│  Today 09:15  │ Sale       │ −3    │ Eggs    │ Till #2     │ #SALE-1024    │
│  Today 09:15  │ Sale       │ −1    │ Milk    │ Till #2     │ #SALE-1024    │
│  Yesterday    │ Wastage    │ −2    │ Milk    │ Spoilage    │ #WASTE-05     │
│  5 Jun 08:30  │ Receipt    │ +1000 │ Eggs    │ GRN-001     │               │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Batch Health Card

Four small stat cards that tell the story at a glance:

| Card | Shows | Colour |
|---|---|---|
| 📦 Items | Count + status mix | Green if all good |
| 📈 Sold % | Percentage of total received sold | Green > 80%, Amber 50–80%, Red < 50% |
| 📉 Waste % | Total waste as % of received | Green < 2%, Amber 2–5%, Red > 5% |
| ⏱ Age | Days since received | Green < 7d, Amber 7–14d, Red > 14d |

### Sparkline Progress Bars

For each item row, a mini progress bar showing sold (green) vs waste (orange) vs remaining (grey):

```
Eggs:     ████████░░░░    sold 300 / remaining 680
Milk:     ████████████████░░░░░░  sold 40 / remaining 8
Bread:    ████████████████████    sold 200 / done ✅
```

---

## 3. The Clearing Wizard — Complete a Batch with Ceremony

When the user clicks "Clear batch" or "Complete" on a batch that still has items remaining, they enter a **step-by-step wizard** (not a boring dialog).

### Wizard Flow

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  Step 1 of 3: Review items      Step 2: Account for deficit     Step 3: Confirm │
│  ──────────────────────────────────────────────────────────────────────────  │
│                                                                            │
│  Great work! Batch #SB-1234 is almost done.                                │
│  You've sold 540 of 1,250 units (72%). Let's account for the rest.        │
│                                                                            │
│  ┌─── Items needing attention ───────────────────────────────────────────┐ │
│  │                                                                       │ │
│  │  Item        Received    Sold    Unsold     Action needed              │ │
│  │  ──────────────────────────────────────────────────────────────────  │ │
│  │  🥚 Eggs      1,000      300      680    → [Account for →]            │ │
│  │                                                                       │ │
│  │  🥛 Milk          50       40       10    → [Account for →]            │ │
│  │                                                                       │ │
│  │  🍞 Bread        200      200        0    → ✅ Fully accounted         │ │
│  │                                                                       │ │
│  └───────────────────────────────────────────────────────────────────────┘ │
│                                                                            │
│                                        [Back]  [Next: Account for items →] │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Step 2: Account for Deficit — The Core UI

For each item with unsold stock, the user distributes the deficit into disposition categories:

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  Step 2 of 3: Account for deficit                                            │
│  ──────────────────────────────────────────────────────────────────────────  │
│                                                                            │
│  🥚 Eggs (Large) — 680 units remaining                                  │
│                                                                           │
│  How were these 680 eggs disposed of?                                     │
│                                                                           │
│  ┌─── Allocation ───────────────────────────────────────────────────────┐ │
│  │  Was sold but not recorded     [    0  ] units                       │ │
│  │  Spoiled / expired             [  120  ] units ──── Reason: Expired  │ │
│  │  Broken / damaged              [   30  ] units ──── Reason: Breakage │ │
│  │  Stolen / missing              [   20  ] units ──── Reason: Theft    │ │
│  │  Donated / sample              [   10  ] units ──── Reason: Sample   │ │
│  │  Still in storage (valid)      [  500  ] units                       │ │
│  │                                 ────────                              │ │
│  │  Total                         [  680  ] ✓ Balanced                  │ │
│  └──────────────────────────────────────────────────────────────────────┘ │
│                                                                            │
│  🥛 Fresh Milk (1L) — 8 units remaining                                    │
│                                                                           │
│  ┌─── Allocation ───────────────────────────────────────────────────────┐ │
│  │  Spoiled / expired             [    8  ] units ──── Reason: Expired  │ │
│  │                                 ────────                              │ │
│  │  Total                         [    8  ] ✓ Balanced                  │ │
│  └──────────────────────────────────────────────────────────────────────┘ │
│                                                                            │
│                                        [Back]    [Next: Review →]         │
└──────────────────────────────────────────────────────────────────────────────┘
```

**Key interaction details:**
- Each category is a number input that auto-sums to show the running total
- The total MUST balance to the remaining quantity before proceeding (validation)
- A green checkmark appears when each item is balanced
- Categories are pre-populated with sensible defaults based on item type (perishables default to "expired," dry goods to "still in storage")
- The user can override any value

### Disposition Categories

| Category | Icon | Movement type | GL impact |
|---|---|---|---|
| Sold (manual) | 💰 | sale | Revenue + COGS |
| Spoiled / expired | 🦠 | wastage (expired) | Shrinkage expense |
| Broken / damaged | 💔 | wastage (breakage) | Shrinkage expense |
| Stolen / missing | 🕵️ | wastage (theft) | Shrinkage expense |
| Donated / sample | 🎁 | wastage (sample) | Shrinkage expense |
| Staff consumption | 👥 | wastage (personal_use) | Shrinkage expense |
| Counting error | 🔢 | adjustment | Inventory adjustment |
| Still in storage | 📦 | (none — remains active) | — |

### Step 3: Confirmation

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  Step 3 of 3: Confirm closure                                                │
│  ──────────────────────────────────────────────────────────────────────────  │
│                                                                            │
│  Summary of Batch #SB-1234                                                 │
│                                                                           │
│  ┌─── Final Accounting ─────────────────────────────────────────────────┐ │
│  │                                                                       │ │
│  │  Total received         1,250 units                                   │ │
│  │  Total sold               540 units  ─── Already recorded              │ │
│  │  Expired                  128 units  ─── Dr Shrinkage: 1,960 KES      │ │
│  │  Breakage                  30 units  ─── Dr Shrinkage:  459 KES      │ │
│  │  Theft                     20 units  ─── Dr Shrinkage:  306 KES      │ │
│  │  Donated                   10 units  ─── Dr Shrinkage:  153 KES      │ │
│  │  Still in storage         500 units  ─── Remains in inventory         │ │
│  │                                 ────────                              │ │
│  │  Total                   1,250 ✓ Balanced                             │ │
│  │                                                                       │ │
│  │  💰 Total shrinkage written off: 2,878 KES                           │ │
│  │  📦 Items remaining after close: 500 units (Eggs)                    │ │
│  │                                                                       │ │
│  └──────────────────────────────────────────────────────────────────────┘ │
│                                                                            │
│  Notes (optional):                                                         │
│  [The eggs from the Tuesday delivery had a high breakage                  │
│   rate. Need to talk to the supplier about packaging.                ]    │
│                                                                            │
│                                        [Back]    [✅ Close batch]          │
└──────────────────────────────────────────────────────────────────────────────┘
```

### After Clearing — The Batch Becomes a Report

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  ← Supply Batches                                                            │
│                                                                              │
│  SB-1234  ·  Tuesday Market Run #7                          Status: 🔒 CLOSED │
│  Farm Fresh Eggs Ltd  ·  Closed 6 Jun 2025 by Jane (Manager)                │
│                                                                              │
│  ┌─── Batch Results ──────────────────────────────────────────────────────┐ │
│  │                                                                         │ │
│  │    1,250 total    540 sold     188 wasted    522 kept                    │ │
│  │    received       (43%)        (15%)         (42%)                       │ │
│  │                                                                         │ │
│  │  ┌─ Breakdown ───────────────────────────────────────────────────────┐  │ │
│  │  │  Spoiled:     128 units   1,960 KES                               │  │ │
│  │  │  Breakage:     30 units     459 KES                               │  │ │
│  │  │  Theft:        20 units     306 KES                               │  │ │
│  │  │  Donated:      10 units     153 KES                               │  │ │
│  │  │  Still in inv: 522 units   note: items remain in stock            │  │ │
│  │  └───────────────────────────────────────────────────────────────────┘  │ │
│  │                                                                         │ │
│  │  💰 Net batch result:                                                   │ │
│  │     Revenue:      13,500 KES                                            │ │
│  │     COGS:         −6,824 KES                                            │ │
│  │     Shrinkage:    −2,878 KES                                            │ │
│  │     ────────────────────────                                             │ │
│  │     Gross profit:  3,798 KES  (28% margin)                              │ │
│  │                                                                         │ │
│  │  [View journal entry →]   [Export report →]   [Reopen batch]           │ │
│  │                                                                         │ │
│  │  Notes from closure:                                                    │ │
│  │  "The eggs from the Tuesday delivery had a high breakage rate. Need     │ │
│  │   to talk to the supplier about packaging."                             │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Auto-Clear Behaviour

### When It Triggers

After every sale, void, or refund that modifies `quantity_remaining`:

```typescript
function autoDetectCompletion(batch: SupplyBatchDetail): boolean {
  return batch.items.every(item => item.quantityRemaining === 0);
}
```

### What Happens

| Condition | Action | UI Notification |
|---|---|---|
| All items at zero after a sale | Batch auto-transitions to `soldout` | Toast: "🎉 Batch SB-1234 is complete! All items sold." |
| User clicks "Complete" on a soldout batch | One-click close, no wizard needed | Batch marked `closed` |
| Some items at zero, some remain | "Clear" button is highlighted | "3 of 5 items completed. Ready to close?" |

### Visual Celebration

When a batch auto-completes:
```
┌──────────────────────────────────────────┐
│  🎉 Batch Complete!                      │
│                                          │
│  Supply Batch #SB-1234 has sold out!     │
│  All 1,250 units accounted for.          │
│                                          │
│  Waste rate: 2% ✅ Excellent             │
│  Profit: +5,100 KES                      │
│                                          │
│  [View summary] [Dismiss]                │
└──────────────────────────────────────────┘
```

---

## 5. Closed Batch Behaviour

### What "Closed" Means

| Aspect | Behaviour |
|---|---|
| Sale picks | ❌ — closed batches are excluded from `lockActiveBatchesForPick` queries |
| Item stock | ✅ — "still in storage" items remain in `Item.current_stock`. The batch is closed, but that stock remains sellable from other batches |
| Batch line status | Each `InventoryBatch` line is marked `depleted` |
| Movement timeline | ✅ Still queryable — closed batches are read-only historical records |
| Re-opening | 🔐 — not allowed. If stock was wrongly written off, create a manual stock adjustment instead |
| Reports | ✅ — still appears in batch reports, supplier reports, wastage analysis |

### Excluding From Sale Picks

When a supply batch is `closed` or `soldout`, none of its `InventoryBatch` lines should be pickable for sales:

```java
// Updated lockActiveBatchesForPick query:
@Query("""
    select b from InventoryBatch b
     where b.businessId = :businessId
       and b.itemId = :itemId
       and b.branchId = :branchId
       and b.status = :status
       and b.quantityRemaining > :minRemaining
       and (b.supplyBatchId is null
            or b.supplyBatchId not in (
                select sb.id from SupplyBatch sb
                 where sb.status in ('closed', 'soldout')
            ))
     order by b.id asc
    """)
List<InventoryBatch> lockActiveBatchesForPick(...);
```

### "Still in Storage" Items After Close

If the user clears a batch but marks some items as "still in storage":
- The batch is `closed` — no more sales from THIS batch specifically
- The items' `quantity_remaining` on `InventoryBatch` is set to 0 (to balance the batch line)
- A `StockMovement` is NOT created for "still in storage" (the stock isn't lost — it's now in the general pool)
- `Item.current_stock` remains unchanged (the stock isn't deducted from overall inventory)

This effectively transfers the stock from "batch-tracked" to "general pooled" inventory.

---

## 6. Deficit Tracking — The Accounting

### What Gets Posted to GL

| Disposition | Debit | Credit | StockMovement? |
|---|---|---|---|
| Sold (already posted) | Cash/MPesa | Revenue | Already exists from sale |
| Spoiled / expired | Inventory Shrinkage | Inventory | ✅ wastage |
| Broken / damaged | Inventory Shrinkage | Inventory | ✅ wastage |
| Stolen / missing | Inventory Shrinkage | Inventory | ✅ wastage |
| Donated / sample | Inventory Shrinkage | Inventory | ✅ wastage |
| Staff consumption | Inventory Shrinkage | Inventory | ✅ wastage |
| Counting error | Inventory Shrinkage | Inventory | ✅ adjustment |
| Still in storage | — (no entry) | — (stock stays) | ❌ No movement |

### The Batch P&L

After closure, every batch has a complete profit & loss statement:

```
Batch P&L — SB-1234 "Tuesday Market Run #7"
─────────────────────────────────────────────
Revenue (from sale_items):          13,500 KES
COGS    (from sale_items):          −6,824 KES
─────────────────────────────────────────────
Gross profit:                        6,676 KES

Shrinkage (from clearance):
  • Expired:  128 units × 15.31     1,960 KES
  • Breakage:  30 units × 15.31       459 KES
  • Theft:     20 units × 15.31       306 KES
  • Donated:   10 units × 15.31       153 KES
─────────────────────────────────────────────
Total shrinkage:                    −2,878 KES

NET BATCH RESULT:                    3,798 KES  (28% margin)
─────────────────────────────────────────────

Inventory impact:
  • Removed from batch accounting:  1,250 units
  • Fully consumed:                   728 units (sold + wasted)
  • Returned to general pool:        522 units (still in storage)
```

---

## 7. Implementation Notes

### Frontend State Machine

```typescript
type BatchClearingState = 
  | { stage: "idle" }                          // No clearing in progress
  | { stage: "review" }                        // Step 1 — review items
  | { stage: "allocating"; itemIndex: number }  // Step 2 — per-item allocation
  | { stage: "confirming" }                     // Step 3 — summary + confirm
  | { stage: "submitting" }                     // Saving
  | { stage: "done"; result: ClearanceResult }  // Complete
```

### Component Tree

```
SupplyBatchDetailPage
├── BatchHeader (name, supplier, status, age)
├── BatchHealthCards (items, sold%, waste%, age)
├── BatchProgressBar
├── ItemsTable
│   └── ItemRow (sparkline, status)
├── MovementTimeline
└── BatchClearingWizard (FormDrawer-based)
    ├── WizardStepReview
    ├── WizardStepAllocation
    │   └── DispositionAllocator (per item)
    │       ├── CategoryInput (× N categories)
    │       └── BalanceValidator (must sum to total)
    ├── WizardStepConfirm
    │   └── BatchSummaryTable
    └── WizardResult
```

### API Contract

```typescript
// POST /api/v1/inventory/supply-batches/{id}/clear
interface ClearBatchRequest {
  items: {
    inventoryBatchId: string;
    disposition: {
      soldUnrecorded: number;
      spoiled: number;
      broken: number;
      stolen: number;
      donated: number;
      staffConsumption: number;
      countingError: number;
      stillInStorage: number;
    };
  }[];
  notes?: string;
}

interface ClearBatchResponse {
  id: string;
  batchNumber: string;
  status: "closed";
  items: {
    inventoryBatchId: string;
    initialQuantity: number;
    previouslySold: number;
    disposed: Record<string, number>;
    writtenOffValue: number;
    stillInStorage: number;
  }[];
  journalEntryId: string;
  totalWriteOffValue: number;
  netProfit: number;
}
```

---

## Summary

| Feature | What the user experiences | What happens in the system |
|---|---|---|
| **Auto-complete** | Toast notification, subtle celebration | `SupplyBatch.status → soldout` |
| **Progress bar** | See at a glance how far along a batch is | `SUM(sold) / SUM(received)` calculation |
| **Clear wizard** | Step-by-step, visually balanced, satisfying close | Writes off each disposition with proper GL |
| **Deficit allocation** | Distribute unsold items into categories | Creates `StockMovement` per category |
| **Still in storage** | Mark items to remain in pool, not wasted | No movement — stock stays in `Item.current_stock` |
| **Batch P&L** | See revenue, COGS, shrinkage, and net profit | Aggregated from `sale_items` + clearance movements |
| **Closed batch** | Read-only report, excluded from picks | Status = `closed`, query filter excludes it |
