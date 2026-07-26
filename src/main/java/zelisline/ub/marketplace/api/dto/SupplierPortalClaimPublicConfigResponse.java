package zelisline.ub.marketplace.api.dto;

public record SupplierPortalClaimPublicConfigResponse(
        boolean portalEnabled,
        boolean claimEnabled,
        boolean allowSelfClaim,
        String claimMethod,
        int codeLength,
        int codeExpiryMinutes,
        int passwordMinLength,
        boolean passwordRequireNumber,
        boolean passwordRequireUppercase,
        boolean passwordRequireSpecial,
        boolean autoLoginAfterSetup
) {
}
