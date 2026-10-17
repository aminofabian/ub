package zelisline.ub.integrations.easytax.api.dto;

public record EasyTaxTestResponse(
        boolean ok,
        String message,
        String checkedAt
) {
}
