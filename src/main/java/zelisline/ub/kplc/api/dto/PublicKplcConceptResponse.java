package zelisline.ub.kplc.api.dto;

import java.math.BigDecimal;

public record PublicKplcConceptResponse(
        String code,
        String label,
        String kind,
        BigDecimal amount
) {
}
