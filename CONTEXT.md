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
