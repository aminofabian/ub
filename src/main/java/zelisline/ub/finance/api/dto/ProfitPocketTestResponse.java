package zelisline.ub.finance.api.dto;

public record ProfitPocketTestResponse(
        /** pending | failed | skipped */
        String status,
        String sendMoneyId,
        String message
) {
}
