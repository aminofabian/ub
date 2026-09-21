package zelisline.ub.finance.api.dto;

public record ExpenseKopokopoPayResponse(
        boolean accepted,
        String disbursementId,
        String kopokopoSendMoneyId,
        String status,
        String message
) {
}
