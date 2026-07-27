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
            String regionHint,
            String supplierNumber,
            /** Why this matched, e.g. phone_last9, email, name, tax_id, supplier_number. */
            List<String> matchReasons
    ) {
        public SupplierDuplicateMatch {
            matchReasons = matchReasons == null ? List.of() : List.copyOf(matchReasons);
        }
    }
}
