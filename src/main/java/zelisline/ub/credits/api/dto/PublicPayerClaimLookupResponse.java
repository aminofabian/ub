package zelisline.ub.credits.api.dto;

import java.util.List;

public record PublicPayerClaimLookupResponse(
        List<PayerClaimMatch> matches
) {
    public record PayerClaimMatch(
            long customerNo,
            String maskedHint,
            String suffix
    ) {
    }
}
