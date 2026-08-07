package zelisline.ub.payments.api.dto;

public record UpdateKioskPayAccountRequest(
        Boolean activate,
        String payoutPhone,
        Boolean storefrontEnabled
) {
}
