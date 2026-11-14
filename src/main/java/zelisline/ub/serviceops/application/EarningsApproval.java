package zelisline.ub.serviceops.application;

import java.util.function.Supplier;

/**
 * Marks a sale as carrying per-person amounts that were already authorised when they were set on
 * the visit. The cashier who takes the payment need not be allowed to set them; whoever typed
 * them was checked then. Bound to the calling thread for the length of one call only.
 */
public final class EarningsApproval {

    private static final ThreadLocal<Boolean> APPROVED = new ThreadLocal<>();

    private EarningsApproval() {
    }

    public static <T> T approved(Supplier<T> sale) {
        APPROVED.set(Boolean.TRUE);
        try {
            return sale.get();
        } finally {
            APPROVED.remove();
        }
    }

    public static boolean isApproved() {
        return Boolean.TRUE.equals(APPROVED.get());
    }
}
