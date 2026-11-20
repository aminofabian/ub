package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Physical notes handed over at the till, as distinct from the cash payment
 * that stays in the drawer. On a split sale the notes can be less than the
 * bill, because M-Pesa or wallet covered the rest. Change is the difference.
 */
public final class CashTender {

    private static final int MONEY_SCALE = 2;

    private CashTender() {
    }

    /**
     * Notes to store on the sale. Null when nothing was handed over, when the
     * sale took no cash, or when the cash payment is larger than the amount due
     * (that excess is wallet credit, not change to give back).
     */
    public static BigDecimal toStore(BigDecimal requested, BigDecimal amountDue, BigDecimal cashPaid) {
        if (requested == null) {
            return null;
        }
        BigDecimal notes = scale(requested);
        BigDecimal paid = scale(cashPaid == null ? BigDecimal.ZERO : cashPaid);
        BigDecimal due = scale(amountDue == null ? BigDecimal.ZERO : amountDue);
        if (paid.signum() <= 0) {
            return null;
        }
        if (paid.compareTo(due) > 0) {
            return null;
        }
        if (notes.compareTo(paid) < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "cashReceived must cover the cash taken");
        }
        return notes;
    }

    /**
     * Change handed back. Null when the notes were not recorded, or when they
     * are less than the cash that stays. Zero when the customer paid the cash
     * share exactly.
     */
    public static BigDecimal changeGiven(BigDecimal cashReceived, BigDecimal cashPaid) {
        if (cashReceived == null || cashPaid == null) {
            return null;
        }
        BigDecimal change = scale(cashReceived).subtract(scale(cashPaid));
        if (change.signum() < 0) {
            return null;
        }
        return change;
    }

    private static BigDecimal scale(BigDecimal amount) {
        return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
