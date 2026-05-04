# ADR 0009 — Loyalty points as a marketing liability, not a contra-revenue

* Status: Accepted (Phase 5)
* Deciders: Phase 5 reviewers
* Date: 2026-05-04

## Context

Phase 5 Slice 4 introduces a single-rate loyalty program with two operations on the customer
ledger: **earn** (issue points on a completed sale) and **redeem** (use points as a tender at
checkout). The plan deliberately leaves the GL treatment as an open question:

> Loyalty — **liability** on balance sheet (extra GL accounts) or **marketing expense** only
> until Phase 6?  *(`PHASE_5_PLAN.md`, Open question 2)*

The first cut of the implementation only handled half of the model: when a customer redeemed
points, the journal posted `Dr 2196 Loyalty Redemption Liability / Cr Sales Revenue`. There was
**no paired credit** anywhere in the codebase, so the liability account drifted permanently
into a debit position on every redemption. That is not double-entry: the books understated the
real obligation and overstated cumulative revenue.

We considered three options:

1. **Drop the liability entirely.** Treat redeem as a discount and reduce credited revenue. No
   GL impact for earn. Easy to implement but masks the real obligation; managers can't see how
   much "money" is owed to customers in points.
2. **Earn → Cr Liability paired with a marketing expense.** Redeem clears the liability into
   revenue. Real double-entry. Slightly more migrations and one more ledger code.
3. **Defer the decision to Phase 6.** Keep shipping the unbalanced ledger.

## Decision

**Adopt option 2.** Loyalty points are a real customer-facing liability the moment they are
issued, and the matching debit is a `5310 Loyalty Marketing Expense` line.

* On **earn** (`LoyaltyPointsService.applyAfterCompletedSale`):
  * Compute KES value of issued points = `earnedPoints × loyalty_kes_per_point`.
  * Post `Dr 5310 Loyalty Marketing Expense / Cr 2196 Loyalty Redemption Liability` for that
    KES amount via `CreditsJournalService.postLoyaltyEarnAccrual`.
* On **redeem** (existing path through `SaleService.postSaleJournal`):
  * The `loyalty_redeem` tender continues to debit `2196` and credit `4000 Sales Revenue`.
    Now that earn has put a credit on `2196`, redemption *clears* a real liability rather than
    creating an unsupported debit.
* On **void** of an earning sale (`reverseLoyaltyForVoidedSale`):
  * Post the inverse: `Dr 2196 / Cr 5310` for the same KES value.
* On **partial refund** (`proportionallyAdjustAfterRefund`):
  * Reverse only the prorated earn portion: `Dr 2196 / Cr 5310` for `clawEarn × kes_per_point`.
* The `redeem`-side reversal on void/refund needs no separate journal line — the original
  redemption is already reversed by the void/refund's symmetric tender entries.

## Consequences

### Positive

* The trial balance stays correct: every point issued is matched by a marketing expense and a
  liability credit. The liability ledger reflects "money we owe customers in points".
* When Phase 6 ships P&L screens, `5310` rolls into operating expenses with no surprise
  reclassifications. Outstanding-points liability is visible on the balance sheet.
* Symmetric void/refund handling drops out of the same journal helpers; no special-case logic.

### Negative / Trade-offs

* Per-sale GL noise: every earning sale produces an extra two-line journal entry. Acceptable
  on POS volume; flagged for batching if profiling shows hot-spotting.
* Points that **never get redeemed** sit on the liability ledger forever. That is the correct
  accounting outcome, but it means Phase 5 ships without a points-expiration policy; expiry is
  out of scope and tracked separately.
* Earn rounding: the KES value posted to GL is computed as `points × kes_per_point` rounded
  HALF_UP to two decimals. The points themselves use FLOOR rounding so the customer is never
  over-credited; the GL amount is therefore at most one cent off per sale.
* Redeem amounts must be exact multiples of `loyalty_kes_per_point`; the service rejects
  fractional remainders (`Redeem amount must be a multiple of …`). That removes the rounding
  ambiguity from the points-cost calculation but pushes the constraint into the cashier UI.

### Migration

* `V36` already creates the `2196 LOYALTY_REDEMPTION_LIABILITY` account.
* This ADR adds `5310 LOYALTY_MARKETING_EXPENSE` to `LedgerAccountCodes` and
  `LedgerBootstrapService.ensureStandardAccounts`. Existing tenants get the account on next
  hit through `ensureStandardAccounts` — no migration needed.

## Alternatives rejected

### A. Redeem-as-discount (drop the liability)

Simpler, but the business loses visibility into the real obligation (points outstanding).
Re-introducing a liability later would require either re-posting historical earnings or
accepting a "starting balance" that breaks the ledger continuity.

### B. Defer the decision

Shipping with an unbalanced ledger means every aged trial balance needs a manual adjustment
journal entry to zero out `2196`. Not acceptable for accountant-facing reports in Phase 6.

## References

* `backend/docs/PHASE_5_PLAN.md` — Slice 4, Open question 2.
* `backend/src/main/java/zelisline/ub/credits/application/LoyaltyPointsService.java` —
  `postLoyaltyEarnAccrual` / `postLoyaltyEarnReversal` call sites.
* `backend/src/main/java/zelisline/ub/credits/application/CreditsJournalService.java` —
  the journal helpers introduced by this ADR.
