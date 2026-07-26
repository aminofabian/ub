package zelisline.ub.marketplace.api.dto;

import java.util.List;

public record SupplierPortalCapabilitiesResponse(
        boolean portalEnabled,
        boolean allowProfileEdits,
        boolean allowPaymentDetailEdits,
        boolean allowProductEdits,
        boolean requireStoreApprovalProductEdits,
        boolean allowInvoiceDownloads,
        boolean allowStatementDownloads,
        String roleKey,
        List<String> permissions,
        boolean canViewMoney,
        boolean canManageTeam
) {
}
