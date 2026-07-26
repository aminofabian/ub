package zelisline.ub.marketplace.application;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.identity.application.TokenHasher;
import zelisline.ub.marketplace.api.dto.SupplierPortalClaimCompleteRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalClaimPublicConfigResponse;
import zelisline.ub.marketplace.api.dto.SupplierPortalClaimSendCodeResponse;
import zelisline.ub.marketplace.api.dto.SupplierPortalClaimVerifyCodeResponse;
import zelisline.ub.marketplace.api.dto.SupplierPortalLoginResponse;
import zelisline.ub.marketplace.domain.BusinessSupplierConnection;
import zelisline.ub.marketplace.domain.BusinessSupplierConnectionStatuses;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierStatuses;
import zelisline.ub.marketplace.domain.SupplierIdentityIndex;
import zelisline.ub.marketplace.domain.SupplierPhoneVerification;
import zelisline.ub.marketplace.domain.SupplierPortalClaimInvite;
import zelisline.ub.marketplace.domain.SupplierUser;
import zelisline.ub.marketplace.domain.SupplierUserRoles;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.marketplace.repository.SupplierIdentityIndexRepository;
import zelisline.ub.marketplace.repository.SupplierPhoneVerificationRepository;
import zelisline.ub.marketplace.repository.SupplierPortalClaimInviteRepository;
import zelisline.ub.marketplace.repository.SupplierUserRepository;
import zelisline.ub.messaging.application.CustomerMessageDispatcher;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.platform.application.PlatformSupplierPortalSettingsService;
import zelisline.ub.platform.domain.PlatformSupplierPortalSettings;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierSlug;
import zelisline.ub.suppliers.repository.SupplierRepository;

import jakarta.servlet.http.HttpServletRequest;

@Service
@RequiredArgsConstructor
public class SupplierPortalClaimService {

    static final Duration SETUP_TOKEN_TTL = Duration.ofMinutes(15);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SupplierPhoneVerificationRepository verificationRepository;
    private final SupplierPortalClaimInviteRepository inviteRepository;
    private final SupplierUserRepository supplierUserRepository;
    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final SupplierIdentityIndexRepository identityIndexRepository;
    private final SupplierRepository supplierRepository;
    private final BusinessSupplierConnectionRepository connectionRepository;
    private final SupplierIdentityIndexService identityIndexService;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final CustomerMessageDispatcher customerMessageDispatcher;
    private final PlatformSupplierPortalSettingsService portalSettingsService;
    private final PasswordEncoder passwordEncoder;
    private final SupplierPortalSessionService sessionService;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    @Transactional(readOnly = true)
    public SupplierPortalClaimPublicConfigResponse publicConfig() {
        PlatformSupplierPortalSettings settings = portalSettingsService.loadSingleton();
        return new SupplierPortalClaimPublicConfigResponse(
                settings.isPortalEnabled(),
                settings.isClaimEnabled(),
                settings.isAllowSelfClaim(),
                settings.getClaimMethod(),
                settings.getCodeLength(),
                settings.getCodeExpiryMinutes(),
                settings.getPasswordMinLength(),
                settings.isPasswordRequireNumber(),
                settings.isPasswordRequireUppercase(),
                settings.isPasswordRequireSpecial(),
                settings.isAutoLoginAfterSetup());
    }

    @Transactional
    public SupplierPortalClaimSendCodeResponse sendCode(String rawPhone) {
        portalSettingsService.requireSelfClaimAllowed();
        PlatformSupplierPortalSettings settings = portalSettingsService.loadSingleton();
        if (PlatformSupplierPortalSettings.CLAIM_METHOD_EMAIL_CODE.equals(settings.getClaimMethod())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Email claim is enabled — use email verification instead");
        }
        if (PlatformSupplierPortalSettings.CLAIM_METHOD_CODE_ONLY.equals(settings.getClaimMethod())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Use your invitation code to claim this account");
        }

        String phone = normalizePhoneOrThrow(rawPhone);
        if (supplierUserRepository.existsByPhone(phone)) {
            return new SupplierPortalClaimSendCodeResponse(
                    phone, maskPhone(phone), null, null, true);
        }

        Instant now = Instant.now();
        Duration cooldown = Duration.ofSeconds(Math.max(0, settings.getResendCooldownSeconds()));
        verificationRepository.findFirstByPhoneAndConsumedAtIsNullOrderByCreatedAtDesc(phone)
                .ifPresent(open -> {
                    if (open.getLockedUntil() != null && open.getLockedUntil().isAfter(now)) {
                        throw new ResponseStatusException(
                                HttpStatus.TOO_MANY_REQUESTS,
                                "Too many attempts. Try again in "
                                        + settings.getLockDurationMinutes() + " minutes");
                    }
                    if (open.getLastSentAt() != null
                            && open.getLastSentAt().plus(cooldown).isAfter(now)
                            && open.getVerifiedAt() == null) {
                        throw new ResponseStatusException(
                                HttpStatus.TOO_MANY_REQUESTS,
                                "Wait before requesting another code");
                    }
                });

        for (SupplierPhoneVerification open : verificationRepository.findByPhoneAndConsumedAtIsNull(phone)) {
            open.setConsumedAt(now);
            verificationRepository.save(open);
        }

        int digits = settings.getCodeLength();
        String code = generateOtp(digits);
        SupplierPhoneVerification challenge = new SupplierPhoneVerification();
        challenge.setPhone(phone);
        challenge.setCodeHash(TokenHasher.sha256Hex(code));
        challenge.setExpiresAt(now.plus(Duration.ofMinutes(settings.getCodeExpiryMinutes())));
        challenge.setAttempts(0);
        challenge.setMaxAttempts(settings.getMaxAttempts());
        challenge.setLastSentAt(now);
        verificationRepository.save(challenge);

        TenantMessagingConfig messaging = messagingSettingsService.resolvePlatformForContactReply();
        if (!messaging.enabled() || (!messaging.smsConfigured() && !messaging.metaWhatsAppConfigured())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Messaging is not configured to send a verification code");
        }

        String message = portalSettingsService.defaultSmsBody(code, settings.getCodeExpiryMinutes());
        var delivery = customerMessageDispatcher.deliver(messaging, phone, message);
        if (!"sent".equals(delivery.outcome()) && !"stub".equals(delivery.outcome())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not send verification code");
        }

        return new SupplierPortalClaimSendCodeResponse(
                phone,
                maskPhone(phone),
                challenge.getExpiresAt(),
                delivery.channel(),
                false);
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public SupplierPortalClaimVerifyCodeResponse verifyCode(String rawPhone, String rawCode) {
        portalSettingsService.requireSelfClaimAllowed();
        PlatformSupplierPortalSettings settings = portalSettingsService.loadSingleton();
        int digits = settings.getCodeLength();

        String phone = normalizePhoneOrThrow(rawPhone);
        String code = rawCode == null ? "" : rawCode.trim();
        if (!code.matches("\\d{" + digits + "}")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Enter the " + digits + "-digit verification code");
        }
        if (supplierUserRepository.existsByPhone(phone)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This phone already has an account — sign in");
        }

        SupplierPhoneVerification challenge = verificationRepository
                .findFirstByPhoneAndConsumedAtIsNullOrderByCreatedAtDesc(phone)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "No active verification — send a code first"));

        Instant now = Instant.now();
        if (challenge.getLockedUntil() != null && challenge.getLockedUntil().isAfter(now)) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many attempts. Try again in " + settings.getLockDurationMinutes() + " minutes");
        }
        if (challenge.getExpiresAt().isBefore(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This code has expired. Request another code.");
        }
        if (challenge.getAttempts() >= challenge.getMaxAttempts()) {
            challenge.setLockedUntil(now.plus(Duration.ofMinutes(settings.getLockDurationMinutes())));
            verificationRepository.save(challenge);
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many attempts. Try again in " + settings.getLockDurationMinutes() + " minutes");
        }

        if (!constantTimeEquals(challenge.getCodeHash(), TokenHasher.sha256Hex(code))) {
            challenge.setAttempts(challenge.getAttempts() + 1);
            if (challenge.getAttempts() >= challenge.getMaxAttempts()) {
                challenge.setLockedUntil(now.plus(Duration.ofMinutes(settings.getLockDurationMinutes())));
            }
            verificationRepository.save(challenge);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Incorrect code");
        }

        String setupToken = newSetupToken();
        Instant tokenExpires = now.plus(SETUP_TOKEN_TTL);
        challenge.setVerifiedAt(now);
        challenge.setSetupTokenHash(TokenHasher.sha256Hex(setupToken));
        challenge.setSetupTokenExpiresAt(tokenExpires);
        verificationRepository.save(challenge);

        return new SupplierPortalClaimVerifyCodeResponse(
                setupToken, tokenExpires, suggestName(phone));
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public SupplierPortalClaimVerifyCodeResponse verifyInvite(String rawCode, String rawPhone) {
        portalSettingsService.requireClaimEnabled();
        PlatformSupplierPortalSettings settings = portalSettingsService.loadSingleton();

        String code = rawCode == null ? "" : rawCode.trim().toUpperCase().replaceAll("[^A-Z0-9]", "");
        if (code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter your verification code");
        }

        SupplierPortalClaimInvite invite = inviteRepository
                .findFirstByCodeHashAndConsumedAtIsNullOrderByCreatedAtDesc(TokenHasher.sha256Hex(code))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Incorrect code"));

        Instant now = Instant.now();
        if (invite.getLockedUntil() != null && invite.getLockedUntil().isAfter(now)) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many attempts. Try again in " + settings.getLockDurationMinutes() + " minutes");
        }
        if (invite.getExpiresAt().isBefore(now)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "This code has expired. Request another code.");
        }
        if (invite.getAttempts() >= invite.getMaxAttempts()) {
            invite.setLockedUntil(now.plus(Duration.ofMinutes(settings.getLockDurationMinutes())));
            inviteRepository.save(invite);
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many attempts. Try again in " + settings.getLockDurationMinutes() + " minutes");
        }

        // Code hash already matched via lookup; still count wrong phone as attempt when invite is phone-bound.
        String phone = null;
        if (rawPhone != null && !rawPhone.isBlank()) {
            phone = normalizePhoneOrThrow(rawPhone);
        } else if (invite.getPhone() != null && !invite.getPhone().isBlank()) {
            phone = invite.getPhone();
        }
        if (invite.getPhone() != null && !invite.getPhone().isBlank() && phone != null
                && !invite.getPhone().equals(phone)) {
            invite.setAttempts(invite.getAttempts() + 1);
            inviteRepository.save(invite);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone does not match this invitation");
        }
        if (phone != null && supplierUserRepository.existsByPhone(phone)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This phone already has an account — sign in");
        }

        MarketplaceSupplier marketplace = marketplaceSupplierRepository.findById(invite.getMarketplaceSupplierId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invitation is invalid"));

        String setupToken = newSetupToken();
        Instant tokenExpires = now.plus(SETUP_TOKEN_TTL);
        invite.setVerifiedAt(now);
        invite.setSetupTokenHash(TokenHasher.sha256Hex(setupToken));
        invite.setSetupTokenExpiresAt(tokenExpires);
        if (phone != null) {
            invite.setPhone(phone);
        }
        inviteRepository.save(invite);

        String suggested = marketplace.getName() != null && !marketplace.getName().isBlank()
                ? marketplace.getName()
                : (phone != null ? suggestName(phone) : "Supplier");
        return new SupplierPortalClaimVerifyCodeResponse(setupToken, tokenExpires, suggested);
    }

    @Transactional
    public SupplierPortalLoginResponse complete(
            SupplierPortalClaimCompleteRequest request,
            HttpServletRequest http
    ) {
        portalSettingsService.requireClaimEnabled();
        PlatformSupplierPortalSettings settings = portalSettingsService.loadSingleton();
        portalSettingsService.validatePassword(request.password());

        String token = request.setupToken() == null ? "" : request.setupToken().trim();
        String tokenHash = TokenHasher.sha256Hex(token);

        SupplierPortalClaimInvite invite = inviteRepository
                .findFirstBySetupTokenHashAndConsumedAtIsNull(tokenHash)
                .orElse(null);
        if (invite != null) {
            return completeInvite(invite, request, settings, http);
        }
        return completeSelfClaim(request, settings, tokenHash, http);
    }

    private SupplierPortalLoginResponse completeInvite(
            SupplierPortalClaimInvite invite,
            SupplierPortalClaimCompleteRequest request,
            PlatformSupplierPortalSettings settings,
            HttpServletRequest http
    ) {
        Instant now = Instant.now();
        if (invite.getVerifiedAt() == null
                || invite.getSetupTokenExpiresAt() == null
                || invite.getSetupTokenExpiresAt().isBefore(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Setup token expired — verify the code again");
        }

        String phone = resolveCompletePhone(request.phone(), invite.getPhone());
        if (supplierUserRepository.existsByPhone(phone)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This phone already has an account — sign in");
        }

        MarketplaceSupplier marketplace = marketplaceSupplierRepository.findById(invite.getMarketplaceSupplierId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invitation is invalid"));

        String email = normalizeEmailOrNull(request.email());
        String displayName = blankToNull(request.name());
        if (displayName == null) {
            displayName = marketplace.getName() != null ? marketplace.getName() : suggestName(phone);
        }

        if (marketplace.getContactPhone() == null || marketplace.getContactPhone().isBlank()) {
            marketplace.setContactPhone(phone);
        }
        if (email != null && (marketplace.getContactEmail() == null || marketplace.getContactEmail().isBlank())) {
            marketplace.setContactEmail(email);
        }
        if (marketplace.getStatus() == null || marketplace.getStatus().isBlank()) {
            marketplace.setStatus(MarketplaceSupplierStatuses.ACTIVE);
        }
        marketplaceSupplierRepository.save(marketplace);
        identityIndexService.upsertMarketplaceSupplier(marketplace);

        SupplierUser user = new SupplierUser();
        user.setMarketplaceSupplierId(marketplace.getId());
        user.setPhone(phone);
        user.setEmail(email);
        user.setName(displayName);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRoleKey(SupplierUserRoles.ADMIN);
        user.setLastLoginAt(now);
        supplierUserRepository.save(user);

        linkLocalsByPhone(marketplace.getId(), phone);

        invite.setConsumedAt(now);
        inviteRepository.save(invite);

        publishClaimed(marketplace.getId(), user.getId(), phone, "invite");
        return loginResponse(user, settings.isAutoLoginAfterSetup(), http);
    }

    private SupplierPortalLoginResponse completeSelfClaim(
            SupplierPortalClaimCompleteRequest request,
            PlatformSupplierPortalSettings settings,
            String tokenHash,
            HttpServletRequest http
    ) {
        portalSettingsService.requireSelfClaimAllowed();

        String phone = normalizePhoneOrThrow(request.phone());
        if (supplierUserRepository.existsByPhone(phone)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This phone already has an account — sign in");
        }

        SupplierPhoneVerification challenge = verificationRepository
                .findFirstBySetupTokenHashAndConsumedAtIsNull(tokenHash)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Invalid or expired setup token — verify the code again"));

        Instant now = Instant.now();
        if (challenge.getVerifiedAt() == null
                || challenge.getSetupTokenExpiresAt() == null
                || challenge.getSetupTokenExpiresAt().isBefore(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Setup token expired — verify the code again");
        }
        if (!phone.equals(challenge.getPhone())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone does not match verified session");
        }

        String email = normalizeEmailOrNull(request.email());
        String displayName = blankToNull(request.name());
        if (displayName == null) {
            displayName = suggestName(phone);
        }

        MarketplaceSupplier marketplace = new MarketplaceSupplier();
        marketplace.setName(displayName);
        marketplace.setContactPhone(phone);
        marketplace.setContactEmail(email);
        marketplace.setStatus(MarketplaceSupplierStatuses.ACTIVE);

        String usernameRaw = blankToNull(request.username());
        if (usernameRaw != null) {
            String username = SupplierPortalProfileService.normalizeUsername(usernameRaw);
            if (username.length() < 2) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username too short");
            }
            if (marketplaceSupplierRepository.existsByUsernameIgnoreCase(username)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is taken");
            }
            marketplace.setUsername(username);
        } else {
            marketplace.setUsername(allocateUsername(displayName, phone));
        }
        marketplaceSupplierRepository.save(marketplace);
        identityIndexService.upsertMarketplaceSupplier(marketplace);

        SupplierUser user = new SupplierUser();
        user.setMarketplaceSupplierId(marketplace.getId());
        user.setPhone(phone);
        user.setEmail(email);
        user.setName(displayName);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRoleKey(SupplierUserRoles.ADMIN);
        user.setLastLoginAt(now);
        supplierUserRepository.save(user);

        linkLocalsByPhone(marketplace.getId(), phone);

        challenge.setConsumedAt(now);
        verificationRepository.save(challenge);

        publishClaimed(marketplace.getId(), user.getId(), phone, "self_claim");
        return loginResponse(user, settings.isAutoLoginAfterSetup(), http);
    }

    private SupplierPortalLoginResponse loginResponse(
            SupplierUser user,
            boolean autoLogin,
            HttpServletRequest http
    ) {
        if (!autoLogin) {
            return sessionService.loginWithoutToken(user);
        }
        return sessionService.issueLogin(user, http);
    }

    private void publishClaimed(String marketplaceSupplierId, String userId, String phone, String path) {
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("marketplaceSupplierId", marketplaceSupplierId);
        diff.put("userId", userId);
        diff.put("phone", phone);
        diff.put("path", path);
        auditEventPublisher.publish(auditEventBuilder
                .builder(AuditEventCategory.SUPPLIERS, AuditEventTypes.SUPPLIER_CLAIMED_ACCOUNT, AuditEventSeverity.INFO)
                .actor(userId, AuditEventActorType.USER)
                .target("marketplace_supplier", marketplaceSupplierId)
                .source("supplier_portal")
                .diff(diff)
                .build());
    }

    private void linkLocalsByPhone(String marketplaceSupplierId, String phone) {
        List<SupplierIdentityIndex> hits = identityIndexRepository.findTenantByPhone(phone);
        for (SupplierIdentityIndex row : hits) {
            if (row.getSupplierId() == null || row.getBusinessId() == null) {
                continue;
            }
            if (connectionRepository.existsByLocalSupplierIdAndStatus(
                    row.getSupplierId(), BusinessSupplierConnectionStatuses.ACTIVE)) {
                continue;
            }
            Supplier local = supplierRepository.findByIdAndDeletedAtIsNull(row.getSupplierId()).orElse(null);
            if (local == null) {
                continue;
            }
            if (local.getMarketplaceSupplierId() != null
                    && !local.getMarketplaceSupplierId().equals(marketplaceSupplierId)) {
                continue;
            }
            if (connectionRepository.existsByBusinessIdAndMarketplaceSupplierId(
                    local.getBusinessId(), marketplaceSupplierId)) {
                continue;
            }
            BusinessSupplierConnection connection = new BusinessSupplierConnection();
            connection.setBusinessId(local.getBusinessId());
            connection.setMarketplaceSupplierId(marketplaceSupplierId);
            connection.setLocalSupplierId(local.getId());
            connection.setStatus(BusinessSupplierConnectionStatuses.ACTIVE);
            connection.setCanViewPurchaseHistory(true);
            connectionRepository.save(connection);
            local.setMarketplaceSupplierId(marketplaceSupplierId);
            supplierRepository.save(local);
            identityIndexService.upsertTenantSupplier(local, phone, null);
        }
    }

    private String allocateUsername(String displayName, String phone) {
        String base = SupplierSlug.slugify(displayName);
        if (base.length() < 2) {
            base = "s" + phone.substring(Math.max(0, phone.length() - 8));
        }
        String candidate = base;
        int i = 0;
        while (marketplaceSupplierRepository.existsByUsernameIgnoreCase(candidate)) {
            i += 1;
            candidate = base + "-" + i;
            if (i > 50) {
                candidate = base + "-" + UUID.randomUUID().toString().substring(0, 6);
                break;
            }
        }
        return candidate;
    }

    private String suggestName(String phone) {
        return identityIndexRepository.findTenantByPhone(phone).stream()
                .map(SupplierIdentityIndex::getSupplierId)
                .filter(id -> id != null && !id.isBlank())
                .map(id -> supplierRepository.findByIdAndDeletedAtIsNull(id).orElse(null))
                .filter(s -> s != null && s.getName() != null && !s.getName().isBlank())
                .map(Supplier::getName)
                .findFirst()
                .orElse("Supplier " + phone.substring(Math.max(0, phone.length() - 4)));
    }

    static String normalizePhoneOrThrow(String raw) {
        String phone = StkPhoneNormalizer.normalize(raw);
        if (phone == null || phone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid phone number");
        }
        return phone;
    }

    private String resolveCompletePhone(String requestPhone, String invitePhone) {
        if (requestPhone != null && !requestPhone.isBlank()) {
            return normalizePhoneOrThrow(requestPhone);
        }
        if (invitePhone != null && !invitePhone.isBlank()) {
            return invitePhone;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid phone number");
    }

    private String normalizeEmailOrNull(String raw) {
        String email = blankToNull(raw);
        if (email == null) {
            return null;
        }
        email = email.trim().toLowerCase();
        if (!email.contains("@") || email.length() < 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid email");
        }
        if (supplierUserRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That email is already in use");
        }
        return email;
    }

    private static String generateOtp(int digits) {
        int bound = (int) Math.pow(10, digits);
        return String.format("%0" + digits + "d", SECURE_RANDOM.nextInt(bound));
    }

    private static String newSetupToken() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    private static String maskPhone(String phone) {
        if (phone.length() <= 4) {
            return "****";
        }
        return "*".repeat(Math.min(phone.length() - 4, 8)) + phone.substring(phone.length() - 4);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
