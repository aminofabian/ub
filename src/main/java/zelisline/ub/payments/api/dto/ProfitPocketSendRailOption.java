package zelisline.ub.payments.api.dto;

/** One outbound rail the merchant can pick for Profit Pocket (Daraja Express or KopoKopo). */
public record ProfitPocketSendRailOption(
        /** daraja | kopokopo */
        String id,
        String label,
        boolean ready,
        String detail
) {
}
