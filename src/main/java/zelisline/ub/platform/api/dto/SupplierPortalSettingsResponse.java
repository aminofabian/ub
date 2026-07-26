package zelisline.ub.platform.api.dto;

import java.time.Instant;

public record SupplierPortalSettingsResponse(
        boolean portalEnabled,
        boolean allowSelfClaim,
        boolean allowProfileEdits,
        boolean allowPaymentDetailEdits,
        boolean allowProductEdits,
        boolean requireStoreApprovalProductEdits,
        boolean allowInvoiceDownloads,
        boolean allowStatementDownloads,
        String portalPublicUrl,
        boolean claimEnabled,
        String claimMethod,
        int codeLength,
        int codeExpiryMinutes,
        int maxAttempts,
        int lockDurationMinutes,
        int resendCooldownSeconds,
        boolean autoLoginAfterSetup,
        int passwordMinLength,
        boolean passwordRequireNumber,
        boolean passwordRequireUppercase,
        boolean passwordRequireSpecial,
        String invitationMessageTemplate,
        String smsTemplate,
        String emailSubjectTemplate,
        String emailBodyTemplate,
        String supportPhone,
        String supportEmail,
        Instant updatedAt
) {
}
