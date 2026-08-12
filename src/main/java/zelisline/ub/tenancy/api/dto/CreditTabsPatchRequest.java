package zelisline.ub.tenancy.api.dto;

public record CreditTabsPatchRequest(
        Boolean allowCashierTabClearance,
        Boolean requirePhoneVerificationForNewTabCustomers,
        Boolean allowCashierSearchCustomersByName
) {
}
