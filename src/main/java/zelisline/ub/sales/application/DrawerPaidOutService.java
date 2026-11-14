package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.repository.ShiftRepository;

/**
 * Cash paid out of an open till for something that is not a sale, refund or drawout — today,
 * paying a staff member their commission and tips (ADR 0014). The expected drawer cash drops by
 * the amount and the movement is written to the cash drawer ledger, so the shift still
 * reconciles at close. The caller posts the journal; nothing here touches the GL.
 *
 * <p>This is deliberately not a cash drawout: drawouts carry cashier approval tiers and may be
 * classified as an expense, whereas a commission payout is authorised by its own permission and
 * settles a liability that was expensed when it was earned.</p>
 */
@Service
@RequiredArgsConstructor
public class DrawerPaidOutService {

    private final OpenShiftResolver openShiftResolver;
    private final ShiftRepository shiftRepository;
    private final CashDrawerLedgerService cashDrawerLedgerService;
    private final AutoShiftService autoShiftService;

    /** @return the id of the shift the cash left */
    @Transactional
    public String payOut(
            String businessId,
            String branchId,
            String tillDeviceKey,
            BigDecimal amount,
            String referenceId,
            String userId
    ) {
        BigDecimal cash = amount.setScale(2, RoundingMode.HALF_UP);
        if (cash.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be positive");
        }
        Shift shift = openShiftResolver.findOpenForUpdate(businessId, branchId, tillDeviceKey)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "Open a shift on this till before paying out of the drawer"));
        BigDecimal next = shift.getExpectedClosingCash().subtract(cash).setScale(2, RoundingMode.HALF_UP);
        if (next.signum() < 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "There is not enough cash in the drawer for this payout");
        }
        shift.setExpectedClosingCash(next);
        shiftRepository.save(shift);
        cashDrawerLedgerService.recordAmount(
                shift.getId(), CashDrawerLedgerService.EVENT_PAID_OUT, CashDrawerLedgerService.REF_ADJUSTMENT,
                referenceId, cash.negate(), CashDrawerLedgerService.CONFIDENCE_CONFIRMED, userId, null);
        return shift.getId();
    }

    /**
     * Cash taken at the till that is not a sale (a booking deposit, a prepaid package). It joins
     * the drawer so the shift still balances.
     *
     * @return the id of the shift the cash went into
     */
    @Transactional
    public String payIn(
            String businessId,
            String branchId,
            String tillDeviceKey,
            BigDecimal amount,
            String referenceId,
            String userId
    ) {
        BigDecimal cash = amount.setScale(2, RoundingMode.HALF_UP);
        if (cash.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be positive");
        }
        // A shop that switched shifts off never opens one by hand: do what a sale does and open
        // the hidden shift, so its first cash of the day can be a deposit.
        if (openShiftResolver.findOpen(businessId, branchId, tillDeviceKey).isEmpty()) {
            autoShiftService.openIfShiftsDisabled(businessId, branchId, tillDeviceKey, userId);
        }
        Shift shift = openShiftResolver.findOpenForUpdate(businessId, branchId, tillDeviceKey)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "Open a shift on this till before taking cash"));
        shift.setExpectedClosingCash(shift.getExpectedClosingCash().add(cash).setScale(2, RoundingMode.HALF_UP));
        shiftRepository.save(shift);
        cashDrawerLedgerService.recordAmount(
                shift.getId(), CashDrawerLedgerService.EVENT_PAID_IN, CashDrawerLedgerService.REF_ADJUSTMENT,
                referenceId, cash, CashDrawerLedgerService.CONFIDENCE_CONFIRMED, userId, null);
        return shift.getId();
    }

    /** Puts a mistaken payout back, if the shift it came from is still open. */
    @Transactional
    public void reverse(String businessId, String shiftId, BigDecimal amount, String referenceId, String userId) {
        BigDecimal cash = amount.setScale(2, RoundingMode.HALF_UP);
        Shift shift = shiftRepository.findByIdAndBusinessIdForUpdate(shiftId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Shift not found"));
        if (!zelisline.ub.sales.SalesConstants.SHIFT_STATUS_OPEN.equals(shift.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That shift is closed; the cash cannot be put back into its drawer");
        }
        shift.setExpectedClosingCash(shift.getExpectedClosingCash().add(cash).setScale(2, RoundingMode.HALF_UP));
        shiftRepository.save(shift);
        cashDrawerLedgerService.recordAmount(
                shift.getId(), CashDrawerLedgerService.EVENT_PAID_IN, CashDrawerLedgerService.REF_ADJUSTMENT,
                referenceId, cash, CashDrawerLedgerService.CONFIDENCE_CONFIRMED, userId, null);
    }
}
