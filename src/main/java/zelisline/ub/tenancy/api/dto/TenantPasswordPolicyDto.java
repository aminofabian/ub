package zelisline.ub.tenancy.api.dto;

/**
 * Password policy hints surfaced to the login UI. Forward-compatible: the
 * frontend should treat unknown fields as "not enforced".
 */
public record TenantPasswordPolicyDto(
        int minLength,
        boolean requireNumber,
        boolean requireSymbol
) {

    private static final int DEFAULT_MIN_LENGTH = 8;

    public static TenantPasswordPolicyDto defaults() {
        return new TenantPasswordPolicyDto(DEFAULT_MIN_LENGTH, false, false);
    }
}
