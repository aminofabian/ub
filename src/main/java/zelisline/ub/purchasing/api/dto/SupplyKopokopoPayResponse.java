package zelisline.ub.purchasing.api.dto;

public record SupplyKopokopoPayResponse(
        boolean accepted,
        String disbursementId,
        String kopokopoSendMoneyId,
        String status,
        String message
) {
}
