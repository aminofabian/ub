package zelisline.ub.credits.api.dto;

public record PublicPayerClaimVerifyResponse(
        String customerId,
        long customerNo,
        String name,
        String phone,
        String tabPath
) {
}
