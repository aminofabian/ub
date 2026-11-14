# Domain Glossary — UB Backend

## Finance / Ledger

* **JournalEntry** — the aggregate root for a double-entry bookkeeping post. Owns its `JournalLine` children and guarantees they balance (`debits == credits`) before persistence.
* **JournalLine** — a single debit or credit line within a `JournalEntry`. Never manipulated directly by application services; created via `JournalEntry.debit(...)` and `JournalEntry.credit(...)`.
* **LedgerPostingPort** — the seam through which all journal entries are persisted. Callers build a `JournalEntry`, then hand it to the port. The port ensures standard accounts exist, asserts balance, and persists header + lines atomically.
* **LedgerAccountResolver** — resolves canonical `LedgerAccountCodes` (e.g., `SALES_REVENUE`) to persisted `LedgerAccount` IDs per business.
* **LedgerAccount** — a tenant-scoped chart-of-accounts row. Identified by a stable business-level `code`.
* **LedgerBootstrapService** — ensures the standard chart of accounts exists for a business. Called automatically by `LedgerPostingPort`; callers should not invoke it directly.

## Sales

* **Sale** — a completed point-of-sale transaction. When completed, triggers journal posting for revenue, COGS, inventory, and tender splits via `LedgerPostingPort`.
* **Refund** — reversal of a prior sale. Posts a reversal journal that mirrors the original sale journal.
* **Void** — cancellation of a sale on the same shift. Posts a full reversal journal.
* **Shift** — a cashier session (open → close). Closing may post a variance journal if counted cash differs from expected.

## Credits

* **CreditsJournalService** — credits-domain vocabulary for common two-line journals (wallet top-ups, AR payments, loyalty accruals/reversals). Maps business operations to the correct debit/credit account codes, then delegates to `LedgerPostingPort`.

## Service sales and staff earnings (ADR 0014)

* **Service line** — a `sale_items` row with `line_kind = SERVICE`: a service, the staff profile that performed it and its planned minutes; no item, no batch, no stock movement, no COGS. Its revenue posts to `SERVICE_REVENUE` (4010), not `SALES_REVENUE`.
* **Performer** — the staff profile that did the work on a service line. Not the cashier (`Sale.soldBy`). A staff profile may have no login.
* **Tip** — money taken with a sale on behalf of a performer. Stored as `sales.tip_total`; not part of `grand_total` and never revenue. The sale's payments cover `Sale.amountDue()` = grand total + tips, and the sale journal credits `TIPS_PAYABLE` (2310).
* **Staff ledger** — `staff_ledger_entries`: one signed row per commission, tip, reversal, adjustment or payout (payouts negative). A staff member's balance is the sum of their rows. Rows are never edited; a correction is another row. `StaffEarningsService` is the only writer.
* **Commission** — whole shillings, half up, on the line total after discount and excluding VAT. Dr `COMMISSION_EXPENSE` (6035) / Cr `COMMISSIONS_PAYABLE` (2300), posted when the sale completes whatever the tender.
* **Payout** — an amount paid to a staff member against their balance; partial payouts are normal, tips are settled first. From the till it is a paid-out movement on the open shift (`DrawerPaidOutService`), not a cash drawout.
* **Reconciliation** — the sum of the staff ledger for a business must equal the credit balance of 2300 + 2310. The commission statement reports it as `reconciled`.

## Held customer money, bookings and packages (ADR 0015)

* **Booking** — a service ticket that has not arrived yet: the same `pos_drafts` row, stage `BOOKED`, with a scheduled start and end. `arrive` makes it a waiting ticket. A staff member cannot hold two overlapping bookings.
* **Deposit** — money taken in advance against a ticket, held in `CUSTOMER_DEPOSITS` (2320) for a named customer. Never in the wallet until it is used, returned as credit or forfeited. `HeldMoneyService` is the only writer.
* **Package** — sessions of one service paid for up front (`customer_service_packages`), held in `DEFERRED_SERVICE_REVENUE` (2330) and recognised one session at a time. The last session carries the rounding.
* **Release to wallet** — how held money is spent: Dr 2320 or 2330 / Cr `CUSTOMER_WALLET_LIABILITY`, then the sale takes the ordinary `customer_wallet` tender. There is no "deposit" or "package" tender.
* **Helper** — a staff profile that shared a service line with its performer, with a share in percent (`service_helpers`, up to five). The performer keeps the rest. Each earns on their own share.
* **Service staff** — the `service_staff` role: someone who serves customers and signs in only to see their own earnings (`/api/v1/service-team/me`). No till.
* **Redo** — a ticket with `redo_of_sale_id`; it closes at Done with no sale and no commission.
* **Booking hours** — `branches.booking_hours`; set means customers may book that branch online, null means they may not.

## Customer visit (ADR 0016)

* **Visit** — what the screens call a service ticket: one customer, one reference, several service lines, one payment. Stored as `pos_drafts` (kind `SERVICE`); never a separate table.
* **Service line execution** — each `pos_draft_lines` row has its own performer, helpers, resource, `line_stage` (`WAITING` / `IN_SERVICE` / `DONE`) and start and finish times. The visit's stage is derived from its lines.
* **Resource** — a chair, bed, room or machine at a branch (`service_resources`), used by one service at a time, with a type, a cleaning time and a block. Independent of people: starting a service needs a free person *and*, if one is named, a free resource.
* **Assign vs start** — assigning names who does a line (and where) without starting it; starting also takes the person and the resource. Payment needs every line assigned, not started.
* **Shop's share** — a line's price less every commission on it. Tips are never part of it.
* **Set amount** — what a person is paid for one service when typed on the visit, replacing their plan for that line only (`performer_amount`, or the third field of a helper). A commission decision: needs permission when set, not when paid.
* **Time off** — dated periods on a staff profile when they cannot be booked (`staff_profiles.time_off`). It stops bookings, not walk-ins.
* **Calendar entry** — one block on the branch calendar: a booking (from the ticket's scheduled time), a service under way or done (from the line's own start and finish), time off, or a resource out of use.

