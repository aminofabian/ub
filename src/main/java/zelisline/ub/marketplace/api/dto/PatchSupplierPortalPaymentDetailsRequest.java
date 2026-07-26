package zelisline.ub.marketplace.api.dto;

import jakarta.validation.constraints.Size;

public record PatchSupplierPortalPaymentDetailsRequest(
        @Size(max = 255) String businessLegalName,
        @Size(max = 64) String paybill,
        @Size(max = 64) String tillNumber,
        @Size(max = 128) String bankName,
        @Size(max = 128) String bankBranch,
        @Size(max = 64) String bankAccountNumber,
        @Size(max = 255) String bankAccountName,
        @Size(max = 64) String mobileMoney,
        @Size(max = 64) String preferredPaymentMethod,
        @Size(max = 64) String taxPin,
        @Size(max = 64) String vatNumber,
        @Size(max = 255) String contactPerson,
        @Size(max = 32) String phone,
        @Size(max = 255) String email
) {
}
