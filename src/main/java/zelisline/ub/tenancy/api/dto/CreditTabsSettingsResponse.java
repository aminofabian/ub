package zelisline.ub.tenancy.api.dto;

public record CreditTabsSettingsResponse(
        boolean allowCashierTabClearance,
        boolean requirePhoneVerificationForNewTabCustomers
) {
    /** Defaults off — admin must enable cashier tab clearance. */
    public static CreditTabsSettingsResponse defaults() {
        return new CreditTabsSettingsResponse(false, true);
    }
}
