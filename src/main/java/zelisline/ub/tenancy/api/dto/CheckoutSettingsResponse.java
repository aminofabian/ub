package zelisline.ub.tenancy.api.dto;

public record CheckoutSettingsResponse(
        boolean captureCustomerForCashAndMpesa
) {
    /**
     * Defaults: customer capture on cash/M-Pesa checkout off
     * (admin must opt in).
     */
    public static CheckoutSettingsResponse defaults() {
        return new CheckoutSettingsResponse(false);
    }
}
