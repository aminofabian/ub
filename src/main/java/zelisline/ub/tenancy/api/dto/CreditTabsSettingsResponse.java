package zelisline.ub.tenancy.api.dto;

public record CreditTabsSettingsResponse(
        boolean allowCashierTabClearance,
        boolean requirePhoneVerificationForNewTabCustomers,
        boolean allowCashierSearchCustomersByName
) {
    /**
     * Defaults: clearance off, phone verification on, name search off
     * (admin must opt in to name lookup on Tab checkout).
     */
    public static CreditTabsSettingsResponse defaults() {
        return new CreditTabsSettingsResponse(false, true, false);
    }
}
