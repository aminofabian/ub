package zelisline.ub.messaging.application;

import java.math.BigDecimal;

/**
 * Thrown when a metered SMS send is attempted with zero available credits.
 * Mapped to HTTP 402 + problem+json by {@code GlobalExceptionHandler} with the
 * fields the frontend needs to render the "Buy credits" CTA.
 */
public class SmsCreditsDepletedException extends RuntimeException {

    private final int available;
    private final int includedRemaining;
    private final int purchasedBalance;
    private final BigDecimal unitPriceKes;

    public SmsCreditsDepletedException(
            String message,
            int available,
            int includedRemaining,
            int purchasedBalance,
            BigDecimal unitPriceKes
    ) {
        super(message);
        this.available = available;
        this.includedRemaining = includedRemaining;
        this.purchasedBalance = purchasedBalance;
        this.unitPriceKes = unitPriceKes;
    }

    public int getAvailable() {
        return available;
    }

    public int getIncludedRemaining() {
        return includedRemaining;
    }

    public int getPurchasedBalance() {
        return purchasedBalance;
    }

    public BigDecimal getUnitPriceKes() {
        return unitPriceKes;
    }
}
