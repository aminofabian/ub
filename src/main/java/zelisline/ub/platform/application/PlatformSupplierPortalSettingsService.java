package zelisline.ub.platform.application;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.api.dto.SupplierPortalSettingsResponse;
import zelisline.ub.platform.api.dto.UpdateSupplierPortalSettingsRequest;
import zelisline.ub.platform.domain.PlatformSupplierPortalSettings;
import zelisline.ub.platform.repository.PlatformSupplierPortalSettingsRepository;

@Service
@RequiredArgsConstructor
public class PlatformSupplierPortalSettingsService {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z0-9_]+)}}");

    private final PlatformSupplierPortalSettingsRepository repository;

    @Transactional(readOnly = true)
    public PlatformSupplierPortalSettings loadSingleton() {
        return repository.findById(PlatformSupplierPortalSettings.SINGLETON_ID)
                .orElseGet(this::createSingleton);
    }

    @Transactional(readOnly = true)
    public SupplierPortalSettingsResponse getForSuperAdmin() {
        return toResponse(loadSingleton());
    }

    @Transactional
    public SupplierPortalSettingsResponse update(UpdateSupplierPortalSettingsRequest body) {
        PlatformSupplierPortalSettings row = loadSingleton();
        if (body.portalEnabled() != null) {
            row.setPortalEnabled(body.portalEnabled());
        }
        if (body.allowSelfClaim() != null) {
            row.setAllowSelfClaim(body.allowSelfClaim());
        }
        if (body.allowProfileEdits() != null) {
            row.setAllowProfileEdits(body.allowProfileEdits());
        }
        if (body.allowPaymentDetailEdits() != null) {
            row.setAllowPaymentDetailEdits(body.allowPaymentDetailEdits());
        }
        if (body.allowProductEdits() != null) {
            row.setAllowProductEdits(body.allowProductEdits());
        }
        if (body.requireStoreApprovalProductEdits() != null) {
            row.setRequireStoreApprovalProductEdits(body.requireStoreApprovalProductEdits());
        }
        if (body.allowInvoiceDownloads() != null) {
            row.setAllowInvoiceDownloads(body.allowInvoiceDownloads());
        }
        if (body.allowStatementDownloads() != null) {
            row.setAllowStatementDownloads(body.allowStatementDownloads());
        }
        if (body.allowFindUnclaimedDrafts() != null) {
            row.setAllowFindUnclaimedDrafts(body.allowFindUnclaimedDrafts());
        }
        if (body.autoPromoteOnCreate() != null) {
            row.setAutoPromoteOnCreate(body.autoPromoteOnCreate());
        }
        if (body.portalPublicUrl() != null) {
            String url = body.portalPublicUrl().trim();
            if (url.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Portal URL cannot be empty");
            }
            row.setPortalPublicUrl(url.replaceAll("/$", ""));
        }
        if (body.claimEnabled() != null) {
            row.setClaimEnabled(body.claimEnabled());
        }
        if (body.claimMethod() != null) {
            row.setClaimMethod(normalizeClaimMethod(body.claimMethod()));
        }
        if (body.codeLength() != null) {
            row.setCodeLength(body.codeLength());
        }
        if (body.codeExpiryMinutes() != null) {
            row.setCodeExpiryMinutes(body.codeExpiryMinutes());
        }
        if (body.maxAttempts() != null) {
            row.setMaxAttempts(body.maxAttempts());
        }
        if (body.lockDurationMinutes() != null) {
            row.setLockDurationMinutes(body.lockDurationMinutes());
        }
        if (body.resendCooldownSeconds() != null) {
            row.setResendCooldownSeconds(body.resendCooldownSeconds());
        }
        if (body.autoLoginAfterSetup() != null) {
            row.setAutoLoginAfterSetup(body.autoLoginAfterSetup());
        }
        if (body.passwordMinLength() != null) {
            row.setPasswordMinLength(body.passwordMinLength());
        }
        if (body.passwordRequireNumber() != null) {
            row.setPasswordRequireNumber(body.passwordRequireNumber());
        }
        if (body.passwordRequireUppercase() != null) {
            row.setPasswordRequireUppercase(body.passwordRequireUppercase());
        }
        if (body.passwordRequireSpecial() != null) {
            row.setPasswordRequireSpecial(body.passwordRequireSpecial());
        }
        if (body.invitationMessageTemplate() != null) {
            row.setInvitationMessageTemplate(blankToNull(body.invitationMessageTemplate()));
        }
        if (body.smsTemplate() != null) {
            row.setSmsTemplate(blankToNull(body.smsTemplate()));
        }
        if (body.emailSubjectTemplate() != null) {
            row.setEmailSubjectTemplate(blankToNull(body.emailSubjectTemplate()));
        }
        if (body.emailBodyTemplate() != null) {
            row.setEmailBodyTemplate(blankToNull(body.emailBodyTemplate()));
        }
        if (body.supportPhone() != null) {
            row.setSupportPhone(blankToNull(body.supportPhone()));
        }
        if (body.supportEmail() != null) {
            row.setSupportEmail(blankToNull(body.supportEmail()));
        }
        row.setUpdatedAt(Instant.now());
        return toResponse(repository.save(row));
    }

    public void requirePortalEnabled() {
        PlatformSupplierPortalSettings settings = loadSingleton();
        if (!settings.isPortalEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Supplier Portal is temporarily unavailable");
        }
    }

    public void requireClaimEnabled() {
        requirePortalEnabled();
        PlatformSupplierPortalSettings settings = loadSingleton();
        if (!settings.isClaimEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Supplier claim is disabled");
        }
    }

    public void requireSelfClaimAllowed() {
        requireClaimEnabled();
        PlatformSupplierPortalSettings settings = loadSingleton();
        if (!settings.isAllowSelfClaim()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Self-claim is disabled — use an invitation code");
        }
    }

    public void validatePassword(String password) {
        PlatformSupplierPortalSettings settings = loadSingleton();
        if (password == null || password.length() < settings.getPasswordMinLength()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Password must be at least " + settings.getPasswordMinLength() + " characters");
        }
        if (settings.isPasswordRequireNumber() && !password.matches(".*\\d.*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must include a number");
        }
        if (settings.isPasswordRequireUppercase() && !password.matches(".*[A-Z].*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must include an uppercase letter");
        }
        if (settings.isPasswordRequireSpecial() && !password.matches(".*[^A-Za-z0-9].*")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Password must include a special character");
        }
    }

    public String renderTemplate(String template, Map<String, String> variables) {
        if (template == null || template.isBlank()) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = variables.getOrDefault(key, "");
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public Map<String, String> templateVariables(
            String supplierName,
            String shopName,
            String claimCode,
            int expiryMinutes
    ) {
        PlatformSupplierPortalSettings settings = loadSingleton();
        Map<String, String> vars = new HashMap<>();
        vars.put("supplier_name", nullToEmpty(supplierName));
        vars.put("shop_name", nullToEmpty(shopName));
        vars.put("claim_code", nullToEmpty(claimCode));
        vars.put("expiry_minutes", String.valueOf(expiryMinutes));
        vars.put("portal_url", claimUrl(null));
        vars.put("support_phone", nullToEmpty(settings.getSupportPhone()));
        vars.put("support_email", nullToEmpty(settings.getSupportEmail()));
        return vars;
    }

    public String claimUrl(String phoneDigits) {
        PlatformSupplierPortalSettings settings = loadSingleton();
        String base = settings.getPortalPublicUrl();
        if (base == null || base.isBlank()) {
            base = "https://kiosk.ke/supplier-portal";
        }
        String url = base.replaceAll("/$", "") + "/claim";
        if (phoneDigits != null && !phoneDigits.isBlank()) {
            url = url + "?phone=" + phoneDigits.replaceAll("\\D", "");
        }
        return url;
    }

    public String defaultSmsBody(String claimCode, int expiryMinutes) {
        PlatformSupplierPortalSettings settings = loadSingleton();
        String template = settings.getSmsTemplate();
        if (template == null || template.isBlank()) {
            return "Your Kiosk supplier code is " + claimCode
                    + ". Valid for " + expiryMinutes + " minutes.";
        }
        return renderTemplate(template, templateVariables(null, null, claimCode, expiryMinutes));
    }

    private PlatformSupplierPortalSettings createSingleton() {
        PlatformSupplierPortalSettings row = new PlatformSupplierPortalSettings();
        row.setId(PlatformSupplierPortalSettings.SINGLETON_ID);
        row.setUpdatedAt(Instant.now());
        return repository.save(row);
    }

    private static String normalizeClaimMethod(String raw) {
        String method = raw == null ? "" : raw.trim().toLowerCase();
        if (PlatformSupplierPortalSettings.CLAIM_METHOD_CODE_ONLY.equals(method)
                || PlatformSupplierPortalSettings.CLAIM_METHOD_EMAIL_CODE.equals(method)
                || PlatformSupplierPortalSettings.CLAIM_METHOD_PHONE_CODE.equals(method)) {
            return method;
        }
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Claim method must be phone_code, code_only, or email_code");
    }

    private static SupplierPortalSettingsResponse toResponse(PlatformSupplierPortalSettings row) {
        return new SupplierPortalSettingsResponse(
                row.isPortalEnabled(),
                row.isAllowSelfClaim(),
                row.isAllowProfileEdits(),
                row.isAllowPaymentDetailEdits(),
                row.isAllowProductEdits(),
                row.isRequireStoreApprovalProductEdits(),
                row.isAllowInvoiceDownloads(),
                row.isAllowStatementDownloads(),
                row.isAllowFindUnclaimedDrafts(),
                row.isAutoPromoteOnCreate(),
                row.getPortalPublicUrl(),
                row.isClaimEnabled(),
                row.getClaimMethod(),
                row.getCodeLength(),
                row.getCodeExpiryMinutes(),
                row.getMaxAttempts(),
                row.getLockDurationMinutes(),
                row.getResendCooldownSeconds(),
                row.isAutoLoginAfterSetup(),
                row.getPasswordMinLength(),
                row.isPasswordRequireNumber(),
                row.isPasswordRequireUppercase(),
                row.isPasswordRequireSpecial(),
                row.getInvitationMessageTemplate(),
                row.getSmsTemplate(),
                row.getEmailSubjectTemplate(),
                row.getEmailBodyTemplate(),
                row.getSupportPhone(),
                row.getSupportEmail(),
                row.getUpdatedAt());
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
