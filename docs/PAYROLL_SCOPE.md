# Payroll — Scope & Roadmap

> **Goal:** Give Palmart shops a practical monthly payroll flow — salaries, advance ledger, payslips, and staff links — without building full HR/compliance software on day one.
>
> **Strategy:** Ship a working MVP on existing backend APIs first, then layer Kenya-specific compliance, exports, and accounting hooks in phases.

**Status:** Phase 1 + 2a–2d + calendar shipped (UI, CSV, statutory, GL expense posting, partial advance repayment, 12-month calendar)  
**Last reviewed:** 2026-08-29  
**Route:** `/payroll` · **API:** `/api/v1/payroll`, `/api/v1/staff`

---

## 1. Core Vision

Payroll in Palmart should feel like a **monthly pay run console** for grocery and retail shops:

- Pick a month, see every staff member in columns
- Set or update salary (effective-dated history)
- Log salary advances with dates and notes
- Deduct advances on pay day (oldest first)
- Mark paid → creates a payslip linked to that staff profile
- Drill into any person from payroll or the Users page

It is **not** (yet) full statutory payroll, HRIS, or disbursement automation — but it should be fully usable for a 5–20 person shop today.

---

## 2. Mental Model

```
users (identity / login)
  └── staff_profiles (HR overlay, 1:1)
        ├── salaries (effective-dated history)
        ├── salary_advances (outstanding → repaid via payslip)
        └── payslips (monthly pay record)
```

**Payroll run** = iterate tenant users (non-buyer, non-terminated) → resolve/create `staff_profile` → lookup current salary as of month-end → sum outstanding advances → optionally pay → create payslip and mark advances repaid.

---

## 3. What Already Exists (Backend)

| Table / entity | Purpose |
|----------------|---------|
| `staff_profiles` | HR record per user: name, title, start date, employment status, bank details, etc. |
| `salaries` | Versioned salary history with `effective_from` |
| `salary_advances` | Amount, date, note, status (`outstanding` \| `repaid`), linked payslip when repaid |
| `payslips` | `period_year`, `period_month`, base, advances deducted, other deductions, net paid, `paid_at`, note |

### API endpoints

| Method | Path | Permission |
|--------|------|------------|
| GET | `/api/v1/staff/profiles` | `staff.profile.read` |
| GET/PATCH | `/api/v1/staff/{userId}/profile` | read / `staff.hr.update` |
| GET/POST | `/api/v1/staff/{userId}/salaries` | `payroll.view` / `payroll.manage` |
| GET/POST | `/api/v1/staff/{userId}/advances` | `payroll.view` / `payroll.manage` |
| GET | `/api/v1/staff/{userId}/payslips` | `payroll.view` |
| GET | `/api/v1/payroll/runs?year=&month=` | `payroll.view` |
| GET | `/api/v1/payroll/advances?status=` | `payroll.view` |
| GET | `/api/v1/payroll/payslips?year=&month=` | `payroll.view` |
| POST | `/api/v1/payroll/runs/pay-all` | `payroll.run` |
| POST | `/api/v1/payroll/runs/{userId}/pay` | `payroll.run` |

### Business rules (MVP)

- Advance repayment on payday: deduct from pool after statutory/other; mark outstanding advances repaid **oldest-first**. If the pool cannot cover the next advance in full, a **partial repayment** is applied and the balance carries forward.
- `payslips.expense_id` reserved for future GL post — **not implemented**.
- Terminated staff excluded from run; `on_leave` still included.
- No PAYE, NSSF, SHIF, or Housing Levy calculations.

### Key files

```
backend/src/main/java/zelisline/ub/payroll/
├── api/PayrollRunController.java, StaffPayrollController.java, StaffProfileController.java
├── application/PayrollService.java, StaffProfileService.java
├── domain/StaffProfile, Salary, SalaryAdvance, Payslip
└── repository/*

backend/src/main/resources/db/migration/
├── V179__staff_payroll_mvp.sql
└── V180__payslips_period_int.sql

frontend/
├── app/(dashboard)/payroll/page.tsx
├── app/(dashboard)/payroll/_components/
├── components/staff/staff-profile-drawer.tsx
└── lib/payroll-utils.ts, lib/api.ts
```

---

## 4. Phase 1 — Shipped (Frontend Completion)

Phase 1 wires up backend capabilities that existed but were hidden or underused in the UI.

### Monthly run tab

| Feature | Description |
|---------|-------------|
| Month navigator | Prev/next, month dropdown, year input, “This month” shortcut |
| Summary cards | Headcount, total base, outstanding advances, net pending |
| Table columns | Employee, Branch, Status, Base, Advances, Net, Paid on, Run status, Actions |
| Clickable advances | Opens per-staff advance ledger drawer |
| Clickable paid date / status | Opens payslip drawer (base, deductions, net, note) |
| Pay confirm drawer | Other deductions + optional note before marking paid |
| Pay all pending | Bulk mark paid via `POST /runs/pay-all` |
| Export CSV | Download monthly run for accountant |
| Staff profile link | Name → profile drawer (also from Users page) |

### Advance ledger tab

| Column | Description |
|--------|-------------|
| Date | When advance was given |
| Staff | Linked to profile drawer |
| Branch | From staff profile |
| Amount | Advance value |
| Status | Outstanding / Repaid |
| Note | Free text |

Filters: **Outstanding** · **All** · **Repaid** · **Export CSV**

Loaded via `GET /api/v1/payroll/advances` (single request, no N+1).

### Payslip history tab

| Column | Description |
|--------|-------------|
| Employee | Opens payslip drawer |
| Base / Advances / Other / Net | Payment breakdown |
| Paid on | Timestamp |
| Note | Optional pay note |

Filtered by selected month via `GET /api/v1/payroll/payslips?year=&month=`. Export CSV included.

### Payslip drawer

- View full breakdown for any paid staff member
- **Print** opens a print-ready payslip document

### Staff profile drawer (payroll sections)

- Salary history (existing)
- **Salary advances** — recent ledger rows with status
- **Payslip history** — period, net paid, paid date

---

## 5. Phase 2 — High-Value Next

| Feature | Status |
|---------|--------|
| **Payslip PDF / share** | Print shipped; PDF/WhatsApp share still open |
| **CSV export** | Shipped (run, advances, payslip history) |
| **Kenya statutory deductions** | Shipped (optional toggle; PAYE/NSSF/SHIF/Housing Levy estimates) |
| **GL / expense posting** | Shipped (`postExpense` → `payslips.expense_id` + finance expense) |
| **Branch payroll filter** | Shipped (`branchId` query on run preview) |
| **On-leave handling** | Shipped (shown in run; blocked from pay / pay all) |
| **Deduction templates** | Shipped (uniform, lost stock, loan presets in pay drawer) |
| **Partial advance repayment** | Shipped (oldest-first; partial on last advance; `amount_repaid` + repayment ledger) |
| **Payroll calendar** | Shipped (`GET /calendar?year=` — 12-month status grid) |
| **Employee self-service** | Shipped (`payroll.self.read`, `/my-pay`, `/pay/{phone}`) |
| **Pro-rata salary** | Mid-month join/leave auto-calculated |
| **Reverse payslip** | Undo mistaken pay (with guardrails + audit) |
| **Audit trail** | Who paid whom, when, with what note |

### Suggested build order

```
Phase 2a   Payslip PDF + CSV export
Phase 2b   GL expense posting (payslips.expense_id)
Phase 2c   Kenya statutory columns (optional shop toggle)
Phase 2d   Partial advance repayment (shipped) + repayment schedule (open)
```

---

## 6. Phase 3 — Creative / Differentiated Ideas

### Operations

1. **Payroll calendar** — Shipped: 12-month grid (green = paid, amber = pending, red = missing salary).
2. **Payroll readiness score** — “4/6 staff ready” with blockers (no salary, no bank details, advance > net).
3. **Monthly comparison** — “August payroll up 12% vs July” in summary cards.
4. **Advance approval flow** — Cashier requests → manager approves → ledger entry.
5. **Deduction templates** — “Uniform”, “Lost stock”, “Loan” as reusable other-deduction presets.

### Staff & pay types

6. **Shift-based pay** — Hourly/daily rate for part-time butchers/clerks (new salary type).
7. **Commission tie-in** — Link sales performance (`StaffPerformanceRow`) to bonus column on payslip.
8. **Non-login workers** — Make `staff_profiles.user_id` nullable for casual staff without accounts.

### Finance & compliance

9. **Till float vs salary advance** — Distinguish shop float from personal advance (similar to supplier advances in Supplies).
10. **Advance repayment schedule** — “KES 2,000/month for 3 months” instead of lump deduct on next pay.
11. **eTIMS-adjacent reporting** — Payroll export aligned with Kenya tax guidance (blog content exists; product does not).
12. **Employee self-service** — Shipped (`/my-pay`, `/pay/{phone}`, `GET /api/v1/payroll/me`).

### Brand / storefront

13. **Public staff board integration** — Tenure + role on storefront staff section; payroll stays private.

---

## 7. Known Gaps & Limitations (MVP)

| Gap | Notes |
|-----|-------|
| No statutory payroll | Optional Kenya estimates shipped — verify with accountant |
| No GL integration | Shipped when `postExpense` is enabled on pay |
| No partial advance splits | Shipped — `amount_repaid` + repayment ledger per payslip |
| No payslip undo | Conflict if payslip already exists for period |
| Bulk pay API | Shipped (`POST /runs/pay-all`) |
| No mobile payroll UI | Web dashboard only |
| `on_leave` in run | Shown but blocked from pay until status changes |
| No pro-rata | Full month salary regardless of start/end date |
| No integration tests | Payroll covered lightly in route guide tests only |

---

## 8. Permissions

| Key | Default roles |
|-----|---------------|
| `staff.profile.read` | owner, admin, manager, cashier, viewer, stock_manager, grocery_clerk, butcher_cashier |
| `staff.hr.read`, `staff.hr.update` | owner, admin, manager |
| `payroll.view`, `payroll.manage`, `payroll.run` | owner, admin, manager |
| `payroll.self.read` | all staff roles (own payslips only) |

---

## 9. UI Structure (Post Phase 1)

```
/payroll
├── [Monthly run]
│   ├── Month navigator (year / month)
│   ├── Summary cards
│   ├── Export CSV · Pay all pending
│   └── Staff table
│       ├── Profile drawer
│       ├── Salary drawer
│       ├── Advance log drawer
│       ├── Advance ledger drawer (per staff)
│       ├── Pay confirm drawer
│       └── Payslip drawer (print)
├── [Calendar]
│   └── 12-month year grid + branch filter (click month → monthly run)
├── [Advance ledger]
│   └── Shop-wide table + CSV export
└── [Payslip history]
    └── Month payslips table + CSV export

Staff self-service (login required):
├── `/my-pay` — personal payslip portal
└── `/pay/0714282874` — bookmarkable phone link (same portal)
```

---

## 10. Recommended Next Step

**Payslip PDF + CSV export** — highest immediate value for a Kenyan grocery shop: owner can pay staff, share proof, and hand a file to the accountant without building compliance engines first.

Alternative if accounting is the priority: **GL expense posting** via `payslips.expense_id`.

---

## 11. Related Docs

- [Phase 12 Plan](./PHASE_12_PLAN.md) — payroll listed as out of scope for Phase 12 (lightweight add-on, not full HR product line)
- Blog clusters mention PAYE/statutory topics in marketing content; product does not implement them yet
