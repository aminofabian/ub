package zelisline.ub.tenancy.api.dto;

public record DomainResponse(
        String id,
        String businessId,
        String domain,
        boolean primary,
        boolean active
) {
}
