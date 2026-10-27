package zelisline.ub.tenancy.api.dto;

/**
 * Successful host lookup with no active tenant. A miss is not an error: the
 * storefront probes every host it renders, including the platform apex.
 */
public record PublicHostUnmappedResponse(boolean mapped) {

    public static final PublicHostUnmappedResponse INSTANCE = new PublicHostUnmappedResponse(false);
}
