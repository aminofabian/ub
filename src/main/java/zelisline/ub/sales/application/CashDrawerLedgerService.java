package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.api.dto.DenominationEntry;
import zelisline.ub.sales.api.dto.DrawerBalanceResponse;
import zelisline.ub.sales.domain.CashDrawerMovement;
import zelisline.ub.sales.domain.CashDrawout;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SalePayment;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.domain.ShiftDenomination;
import zelisline.ub.sales.repository.CashDrawoutRepository;
import zelisline.ub.sales.repository.CashDrawerMovementRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftDenominationRepository;
import zelisline.ub.sales.repository.ShiftRepository;

/**
 * Per-denomination cash drawer ledger for shifts (Phase 1 — CASH_DRAWER_LEDGER_SCOPE.md).
 *
 * <p>Every movement that changes {@code shifts.expected_closing_cash} must be recorded here
 * in the same transaction, so the ledger projection reconciles to the money total. Phase 1
 * records {@link #CONFIDENCE_INFERRED} mixes (fewest-notes decomposition); Phase 2 replaces
 * them with confirmed note mixes sent from the POS.
 *
 * <p>Entity-linked movements ({@code OPENING}, {@code SALE_RECEIVED}, {@code DRAWOUT}, …) are
 * idempotent per (shift, reference, event, denomination) so lazy backfill and offline replay
 * cannot double-count. Repeatable mutations ({@code OPENING_ADJUSTMENT}, {@code SALE_ADJUST})
 * mint a fresh {@code reference_id} per call so a second edit is never silently dropped.
 */
@Service
@RequiredArgsConstructor
public class CashDrawerLedgerService {

    public static final BigDecimal MONEY_TOLERANCE = new BigDecimal("0.01");

    // Event types
    public static final String EVENT_OPENING = "OPENING";
    public static final String EVENT_OPENING_ADJUSTMENT = "OPENING_ADJUSTMENT";
    public static final String EVENT_SALE_RECEIVED = "SALE_RECEIVED";
    public static final String EVENT_SALE_CHANGE = "SALE_CHANGE";
    public static final String EVENT_SALE_ADJUST = "SALE_ADJUST";
    public static final String EVENT_VOID_REVERSAL = "VOID_REVERSAL";
    public static final String EVENT_REFUND = "REFUND";
    public static final String EVENT_DRAWOUT = "DRAWOUT";
    public static final String EVENT_DRAWOUT_REVERSAL = "DRAWOUT_REVERSAL";
    public static final String EVENT_PAID_IN = "PAID_IN";
    public static final String EVENT_PAID_OUT = "PAID_OUT";
    public static final String EVENT_SAFE_DROP = "SAFE_DROP";
    public static final String EVENT_TILL_TRANSFER = "TILL_TRANSFER";
    public static final String EVENT_MANUAL_ADJUSTMENT = "MANUAL_ADJUSTMENT";
    public static final String EVENT_MID_SHIFT_COUNT = "MID_SHIFT_COUNT";

    // Reference types
    public static final String REF_SALE = "SALE";
    public static final String REF_VOID = "VOID";
    public static final String REF_REFUND = "REFUND";
    public static final String REF_DRAWOUT = "DRAWOUT";
    public static final String REF_EXPENSE = "EXPENSE";
    public static final String REF_TRANSFER = "TRANSFER";
    public static final String REF_ADJUSTMENT = "ADJUSTMENT";
    public static final String REF_SHIFT = "SHIFT";

    public static final String CONFIDENCE_CONFIRMED = "CONFIRMED";
    public static final String CONFIDENCE_INFERRED = "INFERRED";

    private final CashDrawerMovementRepository movementRepository;
    private final ShiftRepository shiftRepository;
    private final ShiftDenominationRepository shiftDenominationRepository;
    private final SaleRepository saleRepository;
    private final SalePaymentRepository salePaymentRepository;
    private final CashDrawoutRepository cashDrawoutRepository;

    // ========================================================================
    // SEEDING & BACKFILL
    // ========================================================================

    /**
     * Seeds the OPENING movements for a freshly opened shift. Uses the entered
     * denomination counts when provided (CONFIRMED); otherwise infers a fewest-notes
     * mix from the opening total (INFERRED).
     */
    public void recordOpening(String shiftId, List<DenominationEntry> openingDenoms, BigDecimal openingCash, String performedBy) {
        if (movementRepository.existsByShiftIdAndEventType(shiftId, EVENT_OPENING)) {
            return;
        }
        if (openingDenoms != null && !openingDenoms.isEmpty()) {
            for (DenominationEntry e : openingDenoms) {
                record(shiftId, EVENT_OPENING, REF_SHIFT, shiftId,
                        e.denomination(),
                        e.denominationType() != null ? e.denominationType() : ShiftService.determineDenominationType(e.denomination()),
                        e.quantity(), CONFIDENCE_CONFIRMED, performedBy, null);
            }
        } else if (openingCash != null && openingCash.signum() > 0) {
            recordAmount(shiftId, EVENT_OPENING, REF_SHIFT, shiftId, openingCash, CONFIDENCE_INFERRED, performedBy, null);
        }
    }

    /**
     * Idempotent backfill for shifts that predate the ledger: seeds OPENING from the
     * current opening (counts when available, else inferred mix) and replays completed
     * cash sales + approved drawouts. Never double-counts thanks to the replay key.
     *
     * <p>Known Phase 1 limitation: pre-ledger partial refunds / voids on an in-flight
     * shift are not replayed; any resulting drift is surfaced via the
     * {@link DrawerBalanceResponse#consistent()} flag.
     */
    public void ensureLedgerInitialized(String shiftId) {
        if (movementRepository.existsByShiftIdAndEventType(shiftId, EVENT_OPENING)) {
            return;
        }
        Shift shift = shiftRepository.findById(shiftId).orElse(null);
        if (shift == null) {
            return;
        }

        List<ShiftDenomination> openingRows = shiftDenominationRepository
                .findByShiftIdAndCountTypeOrderByDenominationDesc(shiftId, SalesConstants.DENOM_COUNT_TYPE_OPENING);
        if (!openingRows.isEmpty()) {
            for (ShiftDenomination sd : openingRows) {
                record(shiftId, EVENT_OPENING, REF_SHIFT, shiftId, sd.getDenomination(),
                        sd.getDenominationType(), sd.getQuantity(), CONFIDENCE_CONFIRMED, shift.getOpenedBy(), null);
            }
        } else if (shift.getOpeningCash() != null && shift.getOpeningCash().signum() > 0) {
            recordAmount(shiftId, EVENT_OPENING, REF_SHIFT, shiftId, shift.getOpeningCash(),
                    CONFIDENCE_INFERRED, shift.getOpenedBy(), null);
        }

        for (Sale sale : saleRepository.findByShiftIdAndStatus(shiftId, SalesConstants.SALE_STATUS_COMPLETED)) {
            replaySale(sale);
        }
        for (CashDrawout drawout : cashDrawoutRepository.findByShiftIdAndStatusOrderByCreatedAtDesc(
                shiftId, SalesConstants.DRAWOUT_STATUS_APPROVED)) {
            recordAmount(shiftId, EVENT_DRAWOUT, REF_DRAWOUT, drawout.getId(),
                    drawout.getAmount().negate(), CONFIDENCE_INFERRED, drawout.getApprovedBy(), null);
        }
    }

    private void replaySale(Sale sale) {
        BigDecimal sumCash = BigDecimal.ZERO;
        for (SalePayment p : salePaymentRepository.findBySaleIdOrderBySortOrderAsc(sale.getId())) {
            if (SalesConstants.PAYMENT_METHOD_CASH.equals(p.getMethod())) {
                sumCash = sumCash.add(p.getAmount());
            }
        }
        if (sumCash.signum() <= 0) {
            return;
        }
        BigDecimal received = sale.getCashReceived() != null ? sale.getCashReceived() : sumCash;
        recordAmount(sale.getShiftId(), EVENT_SALE_RECEIVED, REF_SALE, sale.getId(),
                received, CONFIDENCE_INFERRED, sale.getSoldBy(), null);
        if (sale.getCashReceived() != null) {
            BigDecimal change = sale.getCashReceived().subtract(sale.getGrandTotal());
            if (change.signum() > 0) {
                recordAmount(sale.getShiftId(), EVENT_SALE_CHANGE, REF_SALE, sale.getId(),
                        change.negate(), CONFIDENCE_INFERRED, sale.getSoldBy(), null);
            }
        }
    }

    // ========================================================================
    // LIVE RECORDING (called from the services that mutate expected_closing_cash)
    // ========================================================================

    /**
     * Records a completed cash sale. {@code cashReceived} is the physical cash handed
     * over ({@code sales.cash_received}); when it is set, change is the difference to
     * the total. When it is null (split tender / overpay-to-wallet), the received
     * amount is the cash payment sum and no change movement exists — the wallet
     * overpay path never moves physical change.
     */
    public void recordSale(String shiftId, String saleId, BigDecimal cashReceived,
                           BigDecimal grandTotal, BigDecimal sumCashPayments, String performedBy) {
        BigDecimal received = cashReceived != null ? cashReceived : sumCashPayments;
        if (received.signum() > 0) {
            recordAmount(shiftId, EVENT_SALE_RECEIVED, REF_SALE, saleId, received, CONFIDENCE_INFERRED, performedBy, null);
        }
        if (cashReceived != null) {
            BigDecimal change = cashReceived.subtract(grandTotal);
            if (change.signum() > 0) {
                recordAmount(shiftId, EVENT_SALE_CHANGE, REF_SALE, saleId, change.negate(), CONFIDENCE_INFERRED, performedBy, null);
            }
        }
    }

    /**
     * Records an amount-based money move (drawout, refund, void reversal, paid out,
     * opening adjustment…). The denomination mix is inferred with a fewest-notes
     * decomposition; negative amounts are recorded as negative quantity deltas.
     */
    public void recordAmount(String shiftId, String eventType, String referenceType, String referenceId,
                             BigDecimal amount, String confidence, String performedBy, String metadata) {
        if (amount == null || amount.abs().compareTo(MONEY_TOLERANCE) < 0) {
            return;
        }
        int sign = amount.signum();
        for (Map.Entry<Integer, Integer> entry : decompose(amount).entrySet()) {
            record(shiftId, eventType, referenceType, referenceId,
                    entry.getKey(), ShiftService.determineDenominationType(entry.getKey()),
                    sign * entry.getValue(), confidence, performedBy, metadata);
        }
    }

    /**
     * Adjusts the ledger for an opening-float edit ({@code PATCH /shifts/{id}/opening}).
     * When new denomination counts were provided, per-denomination deltas are against the
     * <em>opening float projection only</em> ({@code OPENING} + prior {@code OPENING_ADJUSTMENT}),
     * never the live drawer (which includes sales/drawouts). Otherwise the money delta is
     * decomposed (INFERRED). Each call mints a fresh adjustment id so repeat edits apply.
     * Skipped entirely for pre-ledger shifts — the backfill seed reads the already-corrected opening.
     */
    public void recordOpeningAdjustment(String shiftId, List<DenominationEntry> newOpeningDenoms,
                                        BigDecimal openingDelta, String performedBy, String metadata) {
        if (!movementRepository.existsByShiftIdAndEventType(shiftId, EVENT_OPENING)) {
            return;
        }
        // Unique per mutation — do not reuse shiftId (uq_cdm_replay would drop a second edit).
        String adjustmentId = UUID.randomUUID().toString();
        if (newOpeningDenoms != null) {
            Map<Integer, Integer> openingFloat = openingFloatQuantities(shiftId);
            Map<Integer, Integer> next = new HashMap<>();
            for (DenominationEntry e : newOpeningDenoms) {
                next.put(e.denomination(), e.quantity());
            }
            for (int denom : SalesConstants.KES_DENOMINATIONS) {
                int delta = next.getOrDefault(denom, 0) - openingFloat.getOrDefault(denom, 0);
                if (delta != 0) {
                    record(shiftId, EVENT_OPENING_ADJUSTMENT, REF_ADJUSTMENT, adjustmentId, denom,
                            ShiftService.determineDenominationType(denom), delta, CONFIDENCE_CONFIRMED, performedBy, metadata);
                }
            }
        } else if (openingDelta != null && openingDelta.abs().compareTo(MONEY_TOLERANCE) >= 0) {
            recordAmount(shiftId, EVENT_OPENING_ADJUSTMENT, REF_ADJUSTMENT, adjustmentId,
                    openingDelta, CONFIDENCE_INFERRED, performedBy, metadata);
        }
    }

    /**
     * Opening float only: sums {@code OPENING} + {@code OPENING_ADJUSTMENT} movements.
     * Used when correcting the opening count so mid-shift sales/drawouts are not cancelled.
     */
    public Map<Integer, Integer> openingFloatQuantities(String shiftId) {
        Map<Integer, Integer> qty = new HashMap<>();
        for (CashDrawerMovement m : movementRepository.findByShiftIdOrderByCreatedAtAsc(shiftId)) {
            String event = m.getEventType();
            if (EVENT_OPENING.equals(event) || EVENT_OPENING_ADJUSTMENT.equals(event)) {
                qty.merge(m.getDenomination(), m.getQuantityDelta(), Integer::sum);
            }
        }
        return qty;
    }

    // ========================================================================
    // PROJECTION & RECONCILIATION
    // ========================================================================

    public boolean isInitialized(String shiftId) {
        return movementRepository.existsByShiftIdAndEventType(shiftId, EVENT_OPENING);
    }

    /** Per-denomination expected quantities (opening + movements) for the shift. */
    public Map<Integer, Integer> expectedQuantities(String shiftId) {
        Map<Integer, Integer> qty = new HashMap<>();
        for (CashDrawerMovement m : movementRepository.findByShiftIdOrderByCreatedAtAsc(shiftId)) {
            qty.merge(m.getDenomination(), m.getQuantityDelta(), Integer::sum);
        }
        return qty;
    }

    public DrawerBalanceResponse drawerBalances(Shift shift) {
        ensureLedgerInitialized(shift.getId());
        Map<Integer, Integer> qty = expectedQuantities(shift.getId());
        List<DrawerBalanceResponse.DenominationBalanceRow> rows = new ArrayList<>();
        BigDecimal ledgerTotal = BigDecimal.ZERO;
        for (int denom : SalesConstants.KES_DENOMINATIONS) {
            int quantity = qty.getOrDefault(denom, 0);
            if (quantity == 0) {
                continue;
            }
            BigDecimal total = BigDecimal.valueOf(denom).multiply(BigDecimal.valueOf(quantity))
                    .setScale(2, RoundingMode.HALF_UP);
            ledgerTotal = ledgerTotal.add(total);
            rows.add(new DrawerBalanceResponse.DenominationBalanceRow(
                    denom, ShiftService.determineDenominationType(denom), quantity, total));
        }
        BigDecimal expected = shift.getExpectedClosingCash() != null
                ? shift.getExpectedClosingCash().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        ledgerTotal = ledgerTotal.setScale(2, RoundingMode.HALF_UP);
        boolean consistent = ledgerTotal.subtract(expected).abs().compareTo(MONEY_TOLERANCE) <= 0;
        return new DrawerBalanceResponse(
                shift.getId(), shift.getBranchId(), shift.getOpenedBy(),
                expected, ledgerTotal, consistent, rows);
    }

    // ========================================================================
    // INTERNAL
    // ========================================================================

    private void record(String shiftId, String eventType, String referenceType, String referenceId,
                        int denomination, String denominationType, int quantityDelta,
                        String confidence, String performedBy, String metadata) {
        if (quantityDelta == 0) {
            return;
        }
        if (movementRepository.existsByShiftIdAndReferenceTypeAndReferenceIdAndEventTypeAndDenomination(
                shiftId, referenceType, referenceId, eventType, denomination)) {
            return;
        }
        CashDrawerMovement m = new CashDrawerMovement();
        m.setShiftId(shiftId);
        m.setEventType(eventType);
        m.setReferenceId(referenceId);
        m.setReferenceType(referenceType);
        m.setDenomination(denomination);
        m.setDenominationType(denominationType);
        m.setQuantityDelta(quantityDelta);
        m.setConfidence(confidence);
        m.setPerformedBy(performedBy);
        m.setMetadata(metadata);
        movementRepository.save(m);
    }

    /**
     * Fewest-notes decomposition over the KES denomination set. Sub-1 KES residuals
     * (when totals carry cents) are dropped and surface via the reconciliation flag.
     */
    private static Map<Integer, Integer> decompose(BigDecimal amount) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        long cents = amount.abs()
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
        for (int denom : SalesConstants.KES_DENOMINATIONS) {
            long per = denom * 100L;
            long qty = cents / per;
            if (qty > 0) {
                out.put(denom, (int) qty);
                cents -= qty * per;
            }
        }
        return out;
    }
}
