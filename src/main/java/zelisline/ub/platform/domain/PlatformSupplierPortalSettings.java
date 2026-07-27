package zelisline.ub.platform.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "platform_supplier_portal_settings")
@Getter
@Setter
public class PlatformSupplierPortalSettings {

    public static final String SINGLETON_ID = "00000000-0000-0000-0000-000000000001";

    public static final String CLAIM_METHOD_PHONE_CODE = "phone_code";
    public static final String CLAIM_METHOD_CODE_ONLY = "code_only";
    public static final String CLAIM_METHOD_EMAIL_CODE = "email_code";

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "portal_enabled", nullable = false)
    private boolean portalEnabled = true;

    @Column(name = "allow_self_claim", nullable = false)
    private boolean allowSelfClaim = true;

    @Column(name = "allow_profile_edits", nullable = false)
    private boolean allowProfileEdits = true;

    @Column(name = "allow_payment_detail_edits", nullable = false)
    private boolean allowPaymentDetailEdits = true;

    @Column(name = "allow_product_edits", nullable = false)
    private boolean allowProductEdits = true;

    @Column(name = "require_store_approval_product_edits", nullable = false)
    private boolean requireStoreApprovalProductEdits = false;

    @Column(name = "allow_invoice_downloads", nullable = false)
    private boolean allowInvoiceDownloads = true;

    @Column(name = "allow_statement_downloads", nullable = false)
    private boolean allowStatementDownloads = true;

    /** When true, shop lookup can find draft/unclaimed marketplace suppliers. */
    @Column(name = "allow_find_unclaimed_drafts", nullable = false)
    private boolean allowFindUnclaimedDrafts = true;

    /** When true, creating a local supplier with no match also creates a global passport + S-number. */
    @Column(name = "auto_promote_on_create", nullable = false)
    private boolean autoPromoteOnCreate = true;

    @Column(name = "portal_public_url", length = 512, nullable = false)
    private String portalPublicUrl = "https://kiosk.ke/supplier-portal";

    @Column(name = "claim_enabled", nullable = false)
    private boolean claimEnabled = true;

    @Column(name = "claim_method", length = 32, nullable = false)
    private String claimMethod = CLAIM_METHOD_PHONE_CODE;

    @Column(name = "code_length", nullable = false)
    private int codeLength = 6;

    @Column(name = "code_expiry_minutes", nullable = false)
    private int codeExpiryMinutes = 30;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 5;

    @Column(name = "lock_duration_minutes", nullable = false)
    private int lockDurationMinutes = 15;

    @Column(name = "resend_cooldown_seconds", nullable = false)
    private int resendCooldownSeconds = 60;

    @Column(name = "auto_login_after_setup", nullable = false)
    private boolean autoLoginAfterSetup = true;

    @Column(name = "password_min_length", nullable = false)
    private int passwordMinLength = 8;

    @Column(name = "password_require_number", nullable = false)
    private boolean passwordRequireNumber = false;

    @Column(name = "password_require_uppercase", nullable = false)
    private boolean passwordRequireUppercase = false;

    @Column(name = "password_require_special", nullable = false)
    private boolean passwordRequireSpecial = false;

    @Column(name = "invitation_message_template", columnDefinition = "TEXT")
    private String invitationMessageTemplate;

    @Column(name = "sms_template", columnDefinition = "TEXT")
    private String smsTemplate;

    @Column(name = "email_subject_template", length = 255)
    private String emailSubjectTemplate;

    @Column(name = "email_body_template", columnDefinition = "TEXT")
    private String emailBodyTemplate;

    @Column(name = "support_phone", length = 64)
    private String supportPhone;

    @Column(name = "support_email", length = 191)
    private String supportEmail;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
