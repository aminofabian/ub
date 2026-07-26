package zelisline.ub.marketplace.api.dto;

public record SupplierPortalCapabilitiesResponse(
        boolean portalEnabled,
        boolean allowProfileEdits,
        boolean allowPaymentDetailEdits,
        boolean allowProductEdits,
        boolean requireStoreApprovalProductEdits,
        boolean allowInvoiceDownloads,
        boolean allowStatementDownloads
) {
}
