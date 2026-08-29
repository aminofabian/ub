# Fixed Costs & Recurring Expenses — Scope & Roadmap

> **Goal:** Give Palmart shop owners a practical **rent & bills console** — enter shop rent, utilities, and other repeating costs once, see what's due, and have expenses post to finance automatically (or with a one-tap confirm) — without building full accounting software.
>
> **Strategy:** Wire up **existing Phase 6 backend** (schedules + nightly auto-post) first, then layer calendar, reminders, templates, and landlord/vendor details in phases — mirroring the payroll pay-run pattern.

**Status:** Phase 2 shipped (calendar, occurrences API, post/skip, richer PATCH) — Phase 1 + backend Phase 6  
**Last reviewed:** 2026-08-29  
**Proposed route:** `/fixed-costs` · **API:** `/api/v1/finance/expense-schedules`, `/api/v1/finance/expenses`

---

## 1. Core Vision

Fixed costs in Palmart should feel like a **monthly bills console** for grocery and retail shops:

- Add **shop rent** once (amount, due day, landlord note, branch, payment method)
- Add other repeating costs: **KPLC estimate**, water, security, internet, loan repayment
- See **this month's commitment** before sales come in
- **Calendar view**: green = posted, amber = due soon, red = failed / overdue
- Auto-post on due date (already runs at 02:05 business timezone) **or** remind owner to confirm
- Every posted occurrence creates a real **expense + journal entry** (same pipeline as payroll `postExpense`)

It is **not** (yet) full accounts payable, landlord portals, or M-Pesa disbursement — but it should answer *"what do I owe the building this month?"* for a single-branch or multi-branch shop today.

---

## 2. Mental Model

```
expense_schedules (template: rent, KPLC, etc.)
  └── expense_schedule_occurrences (one row per due date)
        └── expenses (posted spend)
              └── journal_entries (Dr 6000 Operating expenses / Cr cash|M-Pesa|bank)
```

**Monthly run (conceptual)** = for each active schedule → compute occurrences in period → show due / posted / failed → owner confirms or scheduler auto-posts → expense hits GL and (optionally) **cash drawer expected balance**.

Compare to payroll:

| Payroll | Fixed costs |
|---------|-------------|
| `staff_profiles` | `expense_schedules` |
| `salaries` (effective-dated) | schedule `amount` + `start_date` / `end_date` |
| `payslips` (monthly record) | `expense_schedule_occurrences` → `expenses` |
| Pay run calendar | **Fixed-cost calendar** (proposed) |
| Advance ledger | **Occurrence ledger** (due / posted / failed) |

---

## 3. What Already Exists (Backend)

### Tables / entities

| Entity | Purpose |
|--------|---------|
| `expenses` | One-off or generated spend: name, `fixed` \| `variable`, amount, payment method, drawer flag, receipt, ledger account, journal link |
| `expense_schedules` | Recurring template: name, amount, frequency, start/end, active, branch, ledger account |
| `expense_schedule_occurrences` | Per due date: `posted` \| `failed`, links to `expense_id` when successful |

### API endpoints (today)

| Method | Path | Permission | Notes |
|--------|------|------------|-------|
| POST | `/api/v1/finance/expenses` | `finance.expenses.write` | One-off expense + journal |
| GET | `/api/v1/finance/expenses?date=` | `finance.expenses.read` | List for a single day |
| GET | `/api/v1/finance/expenses/{id}` | `finance.expenses.read` | Detail |
| POST | `/api/v1/finance/expense-schedules` | `finance.expenses.write` | Create schedule |
| GET | `/api/v1/finance/expense-schedules` | `finance.expenses.read` | List **active** schedules only |
| PATCH | `/api/v1/finance/expense-schedules/{id}` | `finance.expenses.write` | **`endDate`, `active` only** |
| DELETE | `/api/v1/finance/expense-schedules/{id}` | `finance.expenses.write` | Soft-deactivate (`active=false`) |
| GET | `/api/v1/finance/pulse?date=` | `finance.reports.read` | Today revenue vs expenses total |
| GET | `/api/v1/finance/pl?from=&to=` | `finance.reports.read` | P&L including operating expenses |

### Automation (scheduler)

- **`RecurringExpenseScheduler`** — cron default `0 5 2 * * *` (02:05 daily)
- Enabled when `app.finance.recurring-expenses.enabled=true` (on in `application.properties`)
- **`RecurringExpenseService.processAllBusinessesDueToday()`** — per business timezone, posts all due occurrences
- Frequencies: **daily**, **weekly** (anchor `start_date`), **monthly** (same day-of-month as `start_date`, clamped to month length)
- Idempotent: unique `(schedule_id, occurrence_date)` prevents double-post

### Business rules (MVP backend)

- `category_type`: `fixed` (rent, lease, salaries) or `variable` (utilities, supplies)
- `payment_method`: `cash`, `mpesa_manual`, `bank` → credits the matching asset account
- `include_in_cash_drawer`: when true, reduces **expected closing cash** on open shift (Phase 6 Slice 3)
- Default ledger: **`6000` Operating expenses** if none specified
- Failed posts recorded on occurrence with `failure_reason` (no expense row)

### Key files

```
backend/src/main/java/zelisline/ub/finance/
├── api/FinanceExpensesController.java
├── api/FinanceExpenseSchedulesController.java
├── api/FinanceReportsController.java
├── application/ExpenseService.java
├── application/ExpenseScheduleService.java
├── application/RecurringExpenseService.java
├── scheduler/RecurringExpenseScheduler.java
├── domain/Expense, ExpenseSchedule, ExpenseScheduleOccurrence
└── FinanceConstants.java
```

### Frontend (gaps)

| Area | Status |
|------|--------|
| `fetchFinanceExpenses`, `fetchFinancePulse`, `fetchFinancePL` in `api.ts` | Exists |
| **`fetchExpenseSchedules` / create / patch** | Shipped |
| Dedicated `/fixed-costs` page | Shipped |
| Record one-off expense UI (dashboard) | **Missing** (supply-batch expenses only) |
| Link from Business Hub / Today's takings | Partial (pulse shows `expensesTotal` only) |

---

## 4. Phase 1 — Shipped Backend, Frontend MVP (Recommended First)

Wire up schedules the way payroll wired up payslips: **visible, editable, trustworthy**.

### 1a. Fixed costs hub (`/fixed-costs`)

**Tabs (mirror payroll):**

| Tab | Purpose |
|-----|---------|
| **This month** | Summary cards + table of schedules with next due / last posted |
| **Calendar** | 12-month or month grid of occurrences (see Phase 2) |
| **History** | Posted expenses from schedules + one-offs for selected month |

**Summary cards:**

- Active schedules count
- **Monthly commitment** — sum of active **monthly** schedules (+ prorated weekly/daily if shown)
- Posted this month / still due
- Failed occurrences (needs new list API or client-side from occurrences)

**Schedule table columns:**

| Column | Source |
|--------|--------|
| Name | `expense_schedules.name` |
| Branch | optional `branch_id` |
| Amount | `amount` |
| Frequency | daily / weekly / monthly |
| Next due | computed client-side from `start_date`, `last_generated_on`, `frequency` |
| Last posted | `last_generated_on` |
| Payment | cash / M-Pesa / bank |
| Drawer | yes/no (`include_in_cash_drawer`) |
| Status | active |

**Actions:** Add schedule · Edit end date / deactivate · View occurrence history (when API exists)

### 1b. Add / edit schedule drawer

**Rent wizard (creative UX, simple payload):**

- Step 1 — **What is it?** presets: Shop rent, Stall rent, KPLC / power, Water, Security, Internet, Loan, Other
- Step 2 — **Amount & rhythm** — KES amount, monthly (default for rent), start date, optional end date
- Step 3 — **How you pay** — cash / M-Pesa / bank, include in drawer?, branch
- Step 4 — **Review** — show next 3 due dates before save

Maps 1:1 to existing `PostExpenseScheduleRequest` — no migration required for Phase 1.

### 1c. API client additions

```typescript
// frontend/lib/api.ts (proposed)
fetchExpenseSchedules()
createExpenseSchedule(body)
patchExpenseSchedule(id, { endDate?, active? })
fetchFinanceExpenses(date?)  // already exists
postFinanceExpense(body)     // add wrapper for one-off petty cash
```

### 1d. Navigation

- App shell → **Money in** section → **Fixed costs** (owner / admin / manager)
- Business Hub card: "KES X committed this month" → `/fixed-costs`
- Optional link on `/payments/day` when `expensesTotal` &gt; 0

### Phase 1 exit criteria

- Owner can create monthly shop rent and see it in the list
- After scheduler runs (or manual IT trigger), occurrence appears as expense on due date
- Pulse / P&L reflect posted rent with no duplicate posts

---

## 5. Phase 2 — Calendar, Control & Occurrence APIs ✅ Shipped

Payroll calendar proved valuable; fixed costs need the same **at-a-glance month state**.

### Backend additions (shipped)

| Feature | Endpoint |
|---------|----------|
| **List occurrences** | `GET /api/v1/finance/expense-schedules/occurrences?year=&month=&branchId=` |
| **Calendar summary** | `GET /api/v1/finance/expense-schedules/calendar?year=&branchId=` |
| **Manual post** | `POST /api/v1/finance/expense-schedules/occurrences/{id}/post` |
| **Manual post (no row yet)** | `POST /api/v1/finance/expense-schedules/occurrences/post` `{ scheduleId, occurrenceDate }` |
| **Skip occurrence** | `POST /api/v1/finance/expense-schedules/occurrences/{id}/skip` |
| **Skip (no row yet)** | `POST /api/v1/finance/expense-schedules/occurrences/skip` `{ scheduleId, occurrenceDate }` |
| **Richer PATCH** | `name`, `amount`, `paymentMethod`, `frequency`, `endDate`, `active`, `includeInCashDrawer`, `branchId`, `receiptS3Key`, `expenseLedgerAccountId` |

### Calendar colours (match payroll language)

| Colour | Meaning |
|--------|---------|
| Green | All due occurrences posted for the month |
| Amber | Due dates remain this month |
| Red | Failed occurrence or overdue (remind mode) |
| Neutral | Future / no schedules |

### UI

- Month navigator + grid (reuse payroll calendar component pattern)
- Click month → **This month** tab filtered
- **Failed row** banner with retry

---

## 6. Phase 3 — Creative / Differentiated Ideas

### Operations

1. **Landlord / vendor card** — contact name, phone, M-Pesa number, lease note (new JSON column or `vendors` table); show on schedule drawer; optional WhatsApp "rent reminder" to self
2. **Receipt vault** — attach lease PDF or stamped receipt to schedule; copy to each posted expense
3. **Multi-branch rent roll-up** — "Mirema + Ruaka = KES X / month" on summary
4. **Commitment vs actual** — bar chart: scheduled commitment vs pulse `expensesTotal` for the month
5. **Rent escalation** — `amount` step-up on a date (e.g. +5% annually) without new schedule
6. **Duplicate schedule** — fast setup for new branch with same landlord template

### Finance hooks

7. **Sub-accounts under 6000** — Rent (6010), Utilities (6020), Salaries (6030) for cleaner P&L lines (ledger seed + picker in UI)
8. **Payroll cross-link** — salary schedules visible read-only on fixed-costs ("Payroll handles this") to avoid double-counting
9. **Export CSV** — schedules + occurrences for accountant (mirror payroll CSV)

### Automation & alerts

10. **Due-soon digest** — "Rent KES 45,000 due in 3 days" on overview / WhatsApp / email (reuse notification architecture)
11. **Confirm-to-post mode** — push notification → open app → one tap posts expense (safer than silent auto-post for large rent)
12. **KPLC / utility helper** — link to public KPLC token flow or manual "top-up logged" variable schedule

### Stretch (not MVP)

13. **M-Pesa STK to landlord** — pay rent from app, auto-match to occurrence (similar to customer tab STK)
14. **Landlord read-only portal** — `/landlord/{code}` payment status (Page Seal pattern from customer tab)
15. **Budget envelopes** — cap utilities at KES X/month with amber when pulse exceeds

---

## 7. Known Gaps & Limitations (Today)

| Gap | Notes |
|-----|-------|
| No product UI | Backend + scheduler only |
| PATCH schedule is minimal | Cannot change amount/frequency after create |
| No occurrences API | Must infer from `last_generated_on` + expenses list |
| No `remind` mode | Scheduler always auto-posts; no owner confirm step |
| No vendor/landlord fields | Only free-text `name` (e.g. "Shop rent - Mirema") |
| No expense subcategory | Only `fixed` / `variable`; rent not tagged separately in GL |
| List schedules active-only | No archived/history view without deactivate |
| One-off expense UI absent | API exists; dashboard doesn't expose it |
| Failed occurrence recovery | Logged in DB; no admin UI to retry |
| Integration tests | `RecurringExpenseServiceIT` covers core posting |

---

## 8. Permissions

| Key | Default roles (typical) |
|-----|-------------------------|
| `finance.expenses.read` | owner, admin, manager, cashier |
| `finance.expenses.write` | owner, admin, manager, cashier |
| `finance.reports.read` | owner, admin, manager (pulse / P&L) |

**Recommendation:** Keep fixed-cost **management** on owner/admin/manager; cashiers read-only or no access — configurable later via `finance.expenses.manage` split if needed.

---

## 9. UI Structure (Proposed)

```
/fixed-costs
├── [This month]
│   ├── Month navigator
│   ├── Summary cards (commitment, posted, due, failed)
│   ├── Add schedule · Export CSV
│   └── Schedules table
│       ├── Schedule drawer (edit / deactivate)
│       ├── Occurrence history drawer
│       └── Post now / skip (Phase 2)
├── [Calendar]
│   └── 12-month grid (click → filter This month)
└── [History]
    └── Posted expenses table (from schedules + one-offs)
```

**Related surfaces:**

- `/payments/day` — today's expenses line links to History tab
- `/overview` or Business Hub — monthly commitment chip
- `/payroll` — salaries posted via `postExpense` appear in finance; cross-link note in UI

---

## 10. Recommended Next Step

**Phase 1 frontend MVP** — highest immediate value:

1. Add `fetchExpenseSchedules` + create/patch to `api.ts`
2. Build `/fixed-costs` with **This month** tab + rent wizard
3. Verify one monthly schedule end-to-end against staging scheduler
4. Add nav entry under **Money in**

Alternative if reporting is the priority: expose **one-off expense** form on Today's takings first, then attach schedules.

---

## 11. Configuration & Ops

| Property | Default | Purpose |
|----------|---------|---------|
| `app.finance.recurring-expenses.enabled` | `true` | Master switch for nightly job |
| `app.finance.recurring-expenses.cron` | `0 5 2 * * *` | When auto-post runs |

**Manual trigger (support / dev):** call `RecurringExpenseService.processBusinessForDate(businessId, date)` — consider admin endpoint or documented job hook for backfill.

---

## 12. Related Docs

- [Phase 6 Plan](./PHASE_6_PLAN.md) — expenses, recurrence, drawer, pulse, P&L (source of truth for backend)
- [Payroll Scope](./PAYROLL_SCOPE.md) — parallel "monthly run console" pattern; salary `postExpense` feeds same GL
- [Business Hub Scope](../frontend/docs/BUSINESS_HUB_SCOPE.md) — `fetchFinancePulse` integration point

---

## 13. Example: Mirema Shop Rent

| Field | Value |
|-------|-------|
| Name | Shop rent — Mirema |
| Category | `fixed` |
| Amount | KES 45,000 |
| Frequency | `monthly` |
| Start date | 2026-01-05 (due 5th each month) |
| Payment | `mpesa_manual` |
| Drawer | false (paid from bank/M-Pesa, not till float) |
| Branch | Mirema |

**Expected behaviour:** On each 5th (after 02:05 EAT), scheduler creates expense + journal; owner sees green on calendar for that month; P&L operating expenses include KES 45,000.

---

## 14. Build Order (Suggested)

```
Phase 1a   API client + /fixed-costs This month tab + add schedule drawer
Phase 1b   Nav + Business Hub commitment chip
Phase 2a   Occurrences API + calendar tab
Phase 2b   Manual post / skip + failed retry UI
Phase 3    Landlord fields, reminders, sub-accounts, CSV export
```
