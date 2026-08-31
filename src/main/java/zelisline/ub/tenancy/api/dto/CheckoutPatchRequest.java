package zelisline.ub.tenancy.api.dto;

public record CheckoutPatchRequest(
        Boolean captureCustomerForCashAndMpesa
) {
}
