package zelisline.ub.tenancy.api.dto;

public record RequestDomainHelpResponse(
        boolean accepted,
        String message
) {
}
