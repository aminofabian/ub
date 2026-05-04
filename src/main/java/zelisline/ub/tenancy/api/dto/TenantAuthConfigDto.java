package zelisline.ub.tenancy.api.dto;

import java.util.List;

/**
 * Public auth configuration the login UI consults to decide which methods to
 * render. Real SSO logic isn't built yet; the shape is reserved so the
 * frontend contract is stable when providers come online.
 */
public record TenantAuthConfigDto(
        List<String> methods,
        List<String> ssoProviders,
        TenantPasswordPolicyDto passwordPolicy
) {

    private static final List<String> DEFAULT_METHODS = List.of("password");

    public static TenantAuthConfigDto defaults() {
        return new TenantAuthConfigDto(
                DEFAULT_METHODS,
                List.of(),
                TenantPasswordPolicyDto.defaults()
        );
    }
}
