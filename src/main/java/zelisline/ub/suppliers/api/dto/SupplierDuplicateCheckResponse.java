package zelisline.ub.suppliers.api.dto;

import java.util.List;

public record SupplierDuplicateCheckResponse(
        List<SupplierDuplicateMatch> matches
) {
    public record SupplierDuplicateMatch(
            String confidence,
            String source,
            String localSupplierId,
            String marketplaceSupplierId,
            String name,
            String phone,
            String email,
            String taxId,
            String regionHint
    ) {
    }
}
