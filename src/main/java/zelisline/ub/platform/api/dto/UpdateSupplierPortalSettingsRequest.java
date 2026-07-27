package zelisline.ub.platform.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record UpdateSupplierPortalSettingsRequest(
        Boolean portalEnabled,
        Boolean allowSelfClaim,
        Boolean allowProfileEdits,
        Boolean allowPaymentDetailEdits,
        Boolean allowProductEdits,
        Boolean requireStoreApprovalProductEdits,
        Boolean allowInvoiceDownloads,
        Boolean allowStatementDownloads,
        Boolean allowFindUnclaimedDrafts,
        Boolean autoPromoteOnCreate,
        @Size(max = 512) String portalPublicUrl,
        Boolean claimEnabled,
        @Size(max = 32) String claimMethod,
        @Min(4) @Max(8) Integer codeLength,
        @Min(1) @Max(1440) Integer codeExpiryMinutes,
        @Min(1) @Max(20) Integer maxAttempts,
        @Min(1) @Max(1440) Integer lockDurationMinutes,
        @Min(0) @Max(3600) Integer resendCooldownSeconds,
        Boolean autoLoginAfterSetup,
        @Min(6) @Max(128) Integer passwordMinLength,
        Boolean passwordRequireNumber,
        Boolean passwordRequireUppercase,
        Boolean passwordRequireSpecial,
        String invitationMessageTemplate,
        String smsTemplate,
        @Size(max = 255) String emailSubjectTemplate,
        String emailBodyTemplate,
        @Size(max = 64) String supportPhone,
        @Size(max = 191) String supportEmail
) {
}
