# Daily Stock Audit — Backend Specification

Companion to the frontend spec at  
`frontend/app/(dashboard)/inventory/stock-take/DAILY_AUDIT.md`.

**Status:** Planned — not yet implemented.

---

## Overview

Extend the existing stock-take module (`StockTakeService`, `StockTakeController`) with:

1. A **daily manifest** table — 25 random products sold yesterday, generated once per branch per day.
2. A **session source** flag linking morning/evening sessions to that manifest.
3. **Admin review** endpoints (approve / escalate) separate from inventory `confirmLine`.
4. A **scheduler** that runs each morning before store operations.

Existing general stock take (`source = manual`) remains unchanged.

---

## Related Code (Today)

| File | Role |
|---|---|
| `inventory/application/StockTakeService.java` | Session lifecycle, counts, masking, reconciliation |
| `inventory/api/StockTakeController.java` | REST endpoints under `/api/v1/inventory/stock-take` |
| `inventory/domain/StockTakeSession.java` | Session entity |
| `inventory/domain/StockTakeLine.java` | Line entity |
| `sales/repository/SaleItemRepository.java` | Sales line queries |
| `sales/application/PosTopProductsService.java` | Top sellers — **do not reuse** for random selection |
| `docs/stocktake/STOCKTAKE_IMPROVEMENTS.md` | Partial sessions, `itemIds` on start |

---

## Database Migration (Planned)

Suggested file: `Vxxx__daily_stock_audit.sql`

### Table: `daily_stock_audits`

```sql
CREATE TABLE daily_stock_audits (
    id              VARCHAR(36)  PRIMARY KEY,
    business_id     VARCHAR(36)  NOT NULL,
    branch_id       VARCHAR(36)  NOT NULL,
    audit_date      DATE         NOT NULL,
    item_ids        JSONB        NOT NULL,
    item_count      INT          NOT NULL,
    generated_at    TIMESTAMPTZ  NOT NULL,
    generated_by    VARCHAR(36)  NOT NULL,
    CONSTRAINT uq_daily_stock_audit_branch_date
        UNIQUE (business_id, branch_id, audit_date)
);

CREATE INDEX idx_daily_stock_audits_business_date
    ON daily_stock_audits (business_id, audit_date);
```

### Alter: `stock_take_sessions`

```sql
ALTER TABLE stock_take_sessions
    ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'manual',
    ADD COLUMN daily_audit_id VARCHAR(36) REFERENCES daily_stock_audits(id),
    ADD COLUMN current_line_index INT;

-- source IN ('manual', 'daily_audit')
```

### Alter: `stock_take_lines`

```sql
ALTER TABLE stock_take_lines
    ADD COLUMN review_status VARCHAR(32) NOT NULL DEFAULT 'pending',
    ADD COLUMN review_notes TEXT,
    ADD COLUMN reviewed_by VARCHAR(36),
    ADD COLUMN reviewed_at TIMESTAMPTZ;

-- review_status IN ('pending', 'approved', 'escalated')
```

---

## Daily Manifest Generation

### Scheduler

- **Class (planned):** `DailyStockAuditScheduler`
- **Cron:** `0 0 6 * * *` per business timezone (or single UTC run with business timezone lookup)
- **Idempotent:** If `(business_id, branch_id, audit_date)` exists, skip

### Selection query (requirements)

Input:

- `businessId`, `branchId`
- `soldOn = auditDate.minusDays(1)` (previous calendar day, branch local timezone)

Logic:

1. Distinct `item_id` from completed, non-voided sale lines for that branch on `soldOn`.
2. Exclude non-sellable / deleted catalog items.
3. **Random order** — e.g. `ORDER BY RANDOM()` in PostgreSQL, limited to 25.
4. If pool size &lt; 25, persist all available (`item_count` &lt; 25).

**Do not** rank by units sold (`PosTopProductsService` / `topItemsByUnitsSold`). That selects popular items, not a random audit sample.

Suggested repository method:

```java
List<String> findRandomSoldItemIds(
    String businessId,
    String branchId,
    LocalDate soldOn,
    int limit
);
```

### Persistence

```java
DailyStockAudit audit = new DailyStockAudit();
audit.setBusinessId(businessId);
audit.setBranchId(branchId);
audit.setAuditDate(today);
audit.setItemIds(selectedIds);  // JSON array, order preserved
audit.setItemCount(selectedIds.size());
audit.setGeneratedAt(Instant.now());
audit.setGeneratedBy("system");
repository.save(audit);  // unique constraint prevents duplicates
```

---

## Session Lifecycle (Daily Audit)

### Start session

`POST /api/v1/inventory/stock-take/daily-audits/sessions`

```json
{
  "branchId": "...",
  "sessionType": "morning",
  "auditDate": "2026-07-04"
}
```

Behavior:

1. Load manifest for `(businessId, branchId, auditDate)` — 404 if missing.
2. Reject if an in-progress daily-audit session of same type already exists (or return existing).
3. Create `StockTakeSession` with `source = daily_audit`, `dailyAuditId = manifest.id`.
4. Populate lines from `manifest.itemIds` (same as existing `startSession` line creation).
5. Return **masked** response (`maskSystemQty`).

### Evening session

Load **the same manifest `item_ids`**, not morning confirmed lines.

Current `StockTakeService.startSession` evening fallback loads confirmed morning lines — that path applies only when `source = manual`. For `daily_audit`, always use manifest IDs.

### Count submission

Reuse `patchStockTakeSingleLine` / existing PATCH with masking enforced on response.

Optional: `PATCH .../progress` with `{ "currentLineIndex": 6 }` on session.

### Admin review

`GET .../daily-audits/sessions/{id}/review` — **unmasked**; requires `stocktake.approve`.

Include per line:

- Morning `countedQty` (from morning session line for same `item_id`)
- Evening `countedQty`
- `systemQtySnapshot`
- Expected stock (reuse reconciliation helper)
- Variance
- `review_status`, notes, reviewer, timestamp

Join morning + evening sessions via shared `daily_audit_id`.

---

## Admin Actions

Separate from `confirmLine` (which posts inventory ledger adjustments).

### Approve

`POST .../lines/{lineId}/approve`

```json
{ "notes": "Evening count matches system." }
```

Sets `review_status = approved`, `reviewed_by`, `reviewed_at`, `review_notes`.  
**No stock movement.**

### Escalate

`POST .../lines/{lineId}/escalate`

```json
{ "notes": "Variance of 5 units — check shelf B3." }
```

Sets `review_status = escalated`, audit fields. Item appears in investigations query.

---

## Investigations Query

`GET /api/v1/inventory/stock-take/daily-audits/investigations`

Query params: `branchId`, `from`, `to`, `status` (optional).

Returns lines where `review_status = escalated`, joined with manifest, product, counts, variance.

---

## Response Masking

Existing method: `StockTakeService.maskSystemQty(StockTakeSessionResponse)`.

**Rule:** Any endpoint serving `stocktake.run` users on `source = daily_audit` sessions MUST mask:

- `systemQtySnapshot` on lines and batches
- Expected stock and variance (omit from DTO entirely for counter endpoints)

Admins with `stocktake.approve` receive full DTOs on review/investigations endpoints only.

The business setting `inventory.stocktake.showSystemStockToStockManager` does **not** apply to daily audit sessions.

---

## Permissions

Reuse existing keys:

| Permission | Daily audit use |
|---|---|
| `stocktake.read` | View manifest, investigations (read-only) |
| `stocktake.run` | Counter wizard — start session, submit counts |
| `stocktake.approve` | Review, approve, escalate |
| `stocktake.delete` | Delete erroneous daily-audit sessions (admin only) |

---

## API Summary (Planned)

All under `/api/v1/inventory/stock-take/daily-audits`:

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/today` | read | `?branchId=` — today's manifest + session status |
| GET | `/{auditDate}` | read | Historical manifest |
| POST | `/sessions` | run | Start morning/evening from manifest |
| GET | `/sessions/active` | run | Active daily-audit session for branch + type |
| GET | `/sessions/{id}` | run/approve | Masked for run; full for approve |
| PATCH | `/sessions/{id}/lines/{lineId}` | run | Count + notes |
| PATCH | `/sessions/{id}/progress` | run | Wizard index |
| GET | `/sessions/{id}/review` | approve | Admin table data |
| POST | `/sessions/{id}/lines/{lineId}/approve` | approve | |
| POST | `/sessions/{id}/lines/{lineId}/escalate` | approve | |
| GET | `/investigations` | approve | Escalated lines |

Implement via new `DailyStockAuditController` delegating to `DailyStockAuditService`, which wraps `StockTakeService` for shared line/session logic.

---

## Tests (Planned)

| Test | Assert |
|---|---|
| Scheduler generates exactly one manifest per branch/date | Unique constraint |
| Random selection excludes items not sold yesterday | Pool filter |
| Second scheduler run same day is no-op | Idempotent |
| Morning + evening sessions share item set | Same `daily_audit_id` lines |
| Counter GET masks system qty | `systemQtySnapshot == null` |
| Admin review exposes system qty | Non-null for approve role |
| Approve does not change inventory ledger | Ledger unchanged |
| Escalate appears in investigations | Query returns row |
| Evening daily audit ignores morning confirm state | Manifest IDs either way |

Integration test class suggestion: `DailyStockAuditIT.java` alongside `InventorySlice4IT.java`.

---

## Implementation Order

1. Migration + domain + repository
2. `findRandomSoldItemIds` query + scheduler
3. `DailyStockAuditService.startSession` + masking
4. Admin review + approve/escalate endpoints
5. Investigations query
6. Frontend wizard (see frontend DAILY_AUDIT.md)

---

## See Also

- [STOCKTAKE_IMPROVEMENTS.md](./STOCKTAKE_IMPROVEMENTS.md) — partial sessions, `itemIds`, close logic
- [Frontend DAILY_AUDIT.md](../../../frontend/app/(dashboard)/inventory/stock-take/DAILY_AUDIT.md) — UI spec and phases
