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

