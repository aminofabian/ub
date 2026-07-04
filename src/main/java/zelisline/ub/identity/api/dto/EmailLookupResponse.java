package zelisline.ub.identity.api.dto;

/** Whether a tenant-scoped account exists for the given email. */
public record EmailLookupResponse(
        boolean registered
) {
}
