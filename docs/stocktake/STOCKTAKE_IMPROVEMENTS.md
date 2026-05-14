# Stock-Take System Improvements

## Problem Solved

Previously, when creating a stock-take session, the system would load **ALL** items from the checklist (or all stocked items if the checklist was empty). This meant:

1. You'd have to count 2,000+ items if they were all stocked
2. You couldn't do a partial stock-take of just 20-50 items
3. When closing, the system required confirmation of ALL items (not just the ones you counted)
4. You'd get errors like: "2834 items are still unconfirmed. Pass force=true to close anyway."

## Solutions Implemented

### 1. Optional Item Selection When Starting a Session

**What Changed:**
- `PostStartStockTakeSessionRequest` now accepts an optional `itemIds` field
- You can now specify exactly which items to include in the session
- If `itemIds` is not provided, it falls back to the checklist or all stocked items (backward compatible)

**How to Use:**

```bash
# Option 1: Create a session with specific items (NEW)
POST /api/v1/inventory/stock-take/sessions
{
  "branchId": "branch-123",
  "sessionType": "morning",
  "sessionDate": "2026-05-13",
  "notes": "Partial stock-take - top 50 items",
  "itemIds": ["item-1", "item-2", "item-3", ..., "item-50"]  # Only these 50 items
}

# Option 2: Create a session from checklist or all items (original behavior)
POST /api/v1/inventory/stock-take/sessions
{
  "branchId": "branch-123",
  "sessionType": "morning",
  "sessionDate": "2026-05-13",
  "notes": "Full stock-take"
  // itemIds omitted - uses checklist or all stocked items
}
```

### 2. Smarter Close Logic

**What Changed:**
- `closeSession` now only requires confirmation for items that have **counts entered**
- Items that were never counted (still pending, no counts) are ignored
- Only items with `countedQty` or `adminQuantity` set must be confirmed before closing

**Old Behavior:**
- ❌ Required ALL items in the session to be confirmed (even untouched ones)
- ❌ Forced you to use `force=true` to close if you only counted a subset

**New Behavior:**
- ✅ Only items with counts entered must be confirmed
- ✅ Items with no counts are automatically skipped on close
- ✅ Much cleaner workflow for partial stock-takes

**Example:**
```
Session has 2,837 items loaded from checklist
You count 50 items and confirm all 50
You can now close the session WITHOUT force=true
The 2,787 uncounted items are simply ignored
```

## Database Migration

A new migration file was created: `V72__stocktake_v2_lines_columns.sql`

This migration adds the missing columns to the database:
- `stock_take_lines.status` - tracks pending/submitted/confirmed states
- `stock_take_lines.admin_quantity` - stores admin-approved quantities
- `stock_take_lines.confirmed_by` and `confirmed_at` - tracks who confirmed and when
- `stock_take_sessions.session_type` - morning/evening session types
- `stock_take_sessions.session_date` - session date for uniqueness constraint
- `stocktake_checklist_items` table - for session-type based item filtering

**When you start the application, Flyway will automatically apply this migration.**

## Usage Examples

### Scenario 1: Quick Partial Stock-Take (20 Items)

```bash
# Step 1: Start session with only 20 specific items
curl -X POST http://localhost:5050/api/v1/inventory/stock-take/sessions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "branchId": "main-branch",
    "sessionType": "morning",
    "sessionDate": "2026-05-13",
    "notes": "Quick count - high-value items",
    "itemIds": ["item-101", "item-102", ..., "item-120"]  # 20 items
  }'

# Step 2: Enter counts for all 20 items
curl -X PATCH http://localhost:5050/api/v1/inventory/stock-take/sessions/{sessionId}/lines \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ "counts": [{"lineId": "...", "countedQty": "25"}, ...] }'

# Step 3: Confirm all 20 items
curl -X POST http://localhost:5050/api/v1/inventory/stock-take/sessions/{sessionId}/lines/{lineId}/confirm \
  -H "Authorization: Bearer $TOKEN"

# Step 4: Close the session - NO force=true needed
curl -X POST http://localhost:5050/api/v1/inventory/stock-take/sessions/{sessionId}/close \
  -H "Authorization: Bearer $TOKEN"
```

### Scenario 2: Full Stock-Take (Using Checklist)

```bash
# Step 1: Start session without itemIds - uses checklist
curl -X POST http://localhost:5050/api/v1/inventory/stock-take/sessions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "branchId": "main-branch",
    "sessionType": "morning",
    "sessionDate": "2026-05-13"
  }'

# Steps 2-4: Same as above
# All items from checklist will be loaded, but you can:
# - Count as many or as few as you want
# - Only confirm the ones you've counted
# - Close without force=true
```

## API Changes Summary

### `PostStartStockTakeSessionRequest`

```java
public record PostStartStockTakeSessionRequest(
        @NotBlank String branchId,
        @NotBlank String sessionType,
        @NotNull LocalDate sessionDate,
        String notes,
        List<String> itemIds  // NEW: optional list of specific items to include
) {
}
```

### Close Session Behavior

**Before:**
```
Requires ALL items in session to be confirmed
Error: "2837 items are still unconfirmed"
```

**After:**
```
Only requires items with counts to be confirmed
Items with no counts are automatically skipped
Error (if any): "45 items with counts entered are still unconfirmed"
```

## Backward Compatibility

✅ **Fully backward compatible**
- Existing code that doesn't pass `itemIds` works exactly as before
- The checklist/all-items fallback still works
- All existing endpoints remain unchanged

## Next Steps (Optional Enhancements)

1. **UI Filter for Item Selection**: Add a search/filter UI to select which items to count
2. **Smart Item Recommendations**: Suggest high-value or high-turnover items for partial stock-takes
3. **Barcode Scanning**: Integrate barcode scanning for faster partial stock-takes
4. **Batch Import**: Allow importing item IDs from CSV for large partial stock-takes
