package zelisline.ub.marketplace.api.dto;

public record SupplierPortalPaymentDetailsResponse(
        String marketplaceSupplierId,
        String businessLegalName,
        String paybill,
        String tillNumber,
        String bankName,
        String bankBranch,
        String bankAccountNumber,
        String bankAccountName,
        String mobileMoney,
        String preferredPaymentMethod,
        String taxPin,
        String vatNumber,
        String contactPerson,
        String phone,
        String email,
        boolean editable
) {
}
