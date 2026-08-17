package zelisline.ub.kplc.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PublicKplcTokenResponse(
        Instant purchasedAt,
        BigDecimal amount,
        BigDecimal units,
        String tokenNo,
        String receiptNo,
        String paymentMethod,
        List<PublicKplcConceptResponse> concepts
) {
}
