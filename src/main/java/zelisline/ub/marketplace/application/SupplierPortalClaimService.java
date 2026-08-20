package zelisline.ub.marketplace.application;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

@Service
public class SupplierPortalClaimService {

    private static final Logger log = LoggerFactory.getLogger(SupplierPortalClaimService.class);

    static final Duration SETUP_TOKEN_TTL = Duration.ofMinutes(15);

    private static final String USER_OTP_SEND_FAILED =
            "We couldn't send the code. Check the number and try again in a moment.";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SupplierPhoneVerificationRepository verificationRepository;
    private final SupplierPortalClaimInviteRepository inviteRepository;
    private final SupplierUserRepository supplierUserRepository;
    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final SupplierIdentityIndexRepository identityIndexRepository;
    private final SupplierRepository supplierRepository;
    private final BusinessSupplierConnectionRepository connectionRepository;
    private final SupplierIdentityIndexService identityIndexService;
    private final MarketplaceSupplierPassportService passportService;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final CustomerMessageDispatcher customerMessageDispatcher;
    private final PlatformSupplierPortalSettingsService portalSettingsService;
    private final PasswordEncoder passwordEncoder;
    private final SupplierPortalSessionService sessionService;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;
    private final SupplierPortalShopLinkService shopLinkService;
    private final TransactionTemplate claimAccountTransaction;

    @Value("${app.supplier-portal.claim.return-otp-when-stubbed:true}")
    private boolean returnOtpWhenStubbed;

    public SupplierPortalClaimService(
            SupplierPhoneVerificationRepository verificationRepository,
            SupplierPortalClaimInviteRepository inviteRepository,
            SupplierUserRepository supplierUserRepository,
            MarketplaceSupplierRepository marketplaceSupplierRepository,
            SupplierIdentityIndexRepository identityIndexRepository,
            SupplierRepository supplierRepository,
            BusinessSupplierConnectionRepository connectionRepository,
            SupplierIdentityIndexService identityIndexService,
            MarketplaceSupplierPassportService passportService,
            BusinessCreditMessagingSettingsService messagingSettingsService,
            CustomerMessageDispatcher customerMessageDispatcher,
            PlatformSupplierPortalSettingsService portalSettingsService,
            PasswordEncoder passwordEncoder,
            SupplierPortalSessionService sessionService,
            AuditEventPublisher auditEventPublisher,
            AuditEventBuilder auditEventBuilder,
            SupplierPortalShopLinkService shopLinkService,
            PlatformTransactionManager transactionManager
    ) {
        this.verificationRepository = verificationRepository;
        this.inviteRepository = inviteRepository;
        this.supplierUserRepository = supplierUserRepository;
        this.marketplaceSupplierRepository = marketplaceSupplierRepository;
        this.identityIndexRepository = identityIndexRepository;
        this.supplierRepository = supplierRepository;
        this.connectionRepository = connectionRepository;
        this.identityIndexService = identityIndexService;
        this.passportService = passportService;
        this.messagingSettingsService = messagingSettingsService;
        this.customerMessageDispatcher = customerMessageDispatcher;
        this.portalSettingsService = portalSettingsService;
        this.passwordEncoder = passwordEncoder;
        this.sessionService = sessionService;
        this.auditEventPublisher = auditEventPublisher;
        this.auditEventBuilder = auditEventBuilder;
        this.shopLinkService = shopLinkService;
        this.claimAccountTransaction = new TransactionTemplate(transactionManager);
    }

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
                    phone, maskPhone(phone), null, null, true, null);
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
        // Send WhatsApp + SMS together when both are configured so the code arrives in either inbox.
        boolean smsReady = messaging.enabled() && messaging.smsConfigured();
        boolean waReady = messaging.enabled() && messaging.metaWhatsAppConfigured();
        if (!smsReady && !waReady && !returnOtpWhenStubbed) {
            log.warn(
                    "Supplier claim OTP not sent: messaging not configured phone={} smsReady={} waReady={}",
                    phone, smsReady, waReady);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, USER_OTP_SEND_FAILED);
        }

        String message = portalSettingsService.defaultSmsBody(code, settings.getCodeExpiryMinutes());
        String channel;
        String outcome;
        if (!smsReady && !waReady) {
            log.info("Supplier claim OTP stub (SMS not configured): phone={} code={}", phone, code);
            channel = "sms_stub";
            outcome = "stub";
        } else {
            var delivery = customerMessageDispatcher.deliverBothChannels(messaging, phone, message);
            channel = delivery.channel();
            outcome = delivery.outcome();
            if (!"sent".equals(outcome) && !"stub".equals(outcome)) {
                log.warn(
                        "Supplier claim OTP not sent phone={} channel={} outcome={} detail={}",
                        phone, channel, outcome, delivery.detail());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, USER_OTP_SEND_FAILED);
            }
            if ("stub".equals(outcome)) {
                log.info("Supplier claim OTP stubbed: phone={} code={} channel={}", phone, code, channel);
            }
        }

        String devCode = ("stub".equals(outcome) || (channel != null && channel.contains("stub")))
                ? code
                : (returnOtpWhenStubbed && "stub".equals(outcome) ? code : null);
        return new SupplierPortalClaimSendCodeResponse(
                phone,
                maskPhone(phone),
                challenge.getExpiresAt(),
                channel,
                false,
                devCode);
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

    public SupplierPortalLoginResponse complete(
            SupplierPortalClaimCompleteRequest request,
            HttpServletRequest http
    ) {
        portalSettingsService.requireClaimEnabled();
        PlatformSupplierPortalSettings settings = portalSettingsService.loadSingleton();
        String secretHash = encodeUnlockSecret(request);

        String token = request.setupToken() == null ? "" : request.setupToken().trim();
        if (token.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid or expired setup token — verify the code again");
        }
        String tokenHash = TokenHasher.sha256Hex(token);

        SupplierPortalClaimInvite invite = inviteRepository
                .findFirstBySetupTokenHashAndConsumedAtIsNull(tokenHash)
                .orElse(null);

        // Account create commits in its own TX. Session mint / shop linking must never
        // share that TX — a missing V172 table or a connection unique conflict used to
        // mark the TX rollback-only and surface as a bare HTTP 500.
        SupplierUser user;
        try {
            user = claimAccountTransaction.execute(status -> {
                if (invite != null) {
                    return createUserFromInvite(invite, request, settings, secretHash);
                }
                return createUserFromSelfClaim(request, settings, tokenHash, secretHash);
            });
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (DataIntegrityViolationException ex) {
            throw mapClaimIntegrityViolation(ex);
        } catch (DataAccessException ex) {
            throw mapClaimDataAccess(ex);
        } catch (TransactionException ex) {
            throw mapClaimTransactionFailure(ex);
        } catch (RuntimeException ex) {
            ResponseStatusException nested = findResponseStatus(ex);
            if (nested != null) {
                throw nested;
            }
            if (ex instanceof DataIntegrityViolationException dive) {
                throw mapClaimIntegrityViolation(dive);
            }
            log.error("supplier portal claim failed: {}", rootMessage(ex), ex);
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Could not finish account setup. If you already registered, sign in instead.");
        }
        if (user == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Could not create account — verify the code again");
        }

        // Post-commit side effects only — never join the account TX.
        upsertMarketplaceIdentityBestEffort(user.getMarketplaceSupplierId());
        linkLocalsBestEffort(user.getMarketplaceSupplierId(), user.getPhone());
        publishClaimedBestEffort(user.getMarketplaceSupplierId(), user.getId(), user.getPhone(),
                invite != null ? "invite" : "self_claim");
        return loginResponse(user, settings.isAutoLoginAfterSetup(), http);
    }

    private SupplierUser createUserFromInvite(
            SupplierPortalClaimInvite inviteRef,
            SupplierPortalClaimCompleteRequest request,
            PlatformSupplierPortalSettings settings,
            String secretHash
    ) {
        // Reload inside the TX — the lookup above is outside and may be detached.
        SupplierPortalClaimInvite invite = inviteRepository.findById(inviteRef.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Invalid or expired setup token — verify the code again"));

        Instant now = Instant.now();
        if (invite.getConsumedAt() != null
                || invite.getVerifiedAt() == null
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
        applyDeskCard(marketplace, request, phone);
        if (marketplace.getStatus() == null || marketplace.getStatus().isBlank()) {
            marketplace.setStatus(MarketplaceSupplierStatuses.ACTIVE);
        }
        if (marketplace.getUsername() == null || marketplace.getUsername().isBlank()) {
            marketplace.setUsername(allocateUsername(displayName, phone));
        }
        if (MarketplaceSupplierNaming.isPlaceholderName(marketplace.getName())
                && displayName != null
                && !MarketplaceSupplierNaming.isPlaceholderName(displayName)) {
            marketplace.setName(displayName);
        }
        marketplaceSupplierRepository.saveAndFlush(marketplace);
        passportService.ensureNumberAndIndex(marketplace);
        String localPreferred = suggestName(phone);
        if (!MarketplaceSupplierNaming.isPlaceholderName(localPreferred)) {
            passportService.upgradeNameIfPlaceholder(marketplace, localPreferred);
        }

        SupplierUser user = new SupplierUser();
        user.setMarketplaceSupplierId(marketplace.getId());
        user.setPhone(phone);
        user.setEmail(email);
        user.setName(MarketplaceSupplierNaming.preferDisplayName(displayName, marketplace.getName()));
        user.setPasswordHash(secretHash);
        user.setRoleKey(SupplierUserRoles.ADMIN);
        user.setActive(true);
        user.setLastLoginAt(now);
        supplierUserRepository.saveAndFlush(user);

        invite.setConsumedAt(now);
        inviteRepository.saveAndFlush(invite);
        return user;
    }

    private SupplierUser createUserFromSelfClaim(
            SupplierPortalClaimCompleteRequest request,
            PlatformSupplierPortalSettings settings,
            String tokenHash,
            String secretHash
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

        // Prefer an existing marketplace passport for this phone (invite / shop provision)
        // so we don't orphan connections that already point at it.
        MarketplaceSupplier marketplace = findReusableMarketplaceByPhone(phone).orElse(null);
        if (marketplace == null) {
            marketplace = new MarketplaceSupplier();
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
                if (username.length() > 64) {
                    username = username.substring(0, 64).replaceAll("-+$", "");
                }
                if (marketplaceSupplierRepository.existsByUsernameIgnoreCase(username)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is taken");
                }
                marketplace.setUsername(username);
            } else {
                marketplace.setUsername(allocateUsername(displayName, phone));
            }
            applyDeskCard(marketplace, request, phone);
            marketplaceSupplierRepository.saveAndFlush(marketplace);
        } else {
            if (marketplace.getContactPhone() == null || marketplace.getContactPhone().isBlank()) {
                marketplace.setContactPhone(phone);
            }
            if (email != null && (marketplace.getContactEmail() == null || marketplace.getContactEmail().isBlank())) {
                marketplace.setContactEmail(email);
            }
            applyDeskCard(marketplace, request, phone);
            // Prefer a real shop/claim name over phone-derived "Supplier 2874".
            if (MarketplaceSupplierNaming.isPlaceholderName(marketplace.getName())
                    && displayName != null
                    && !MarketplaceSupplierNaming.isPlaceholderName(displayName)) {
                marketplace.setName(displayName);
            } else if ((marketplace.getName() == null || marketplace.getName().isBlank())
                    && displayName != null) {
                marketplace.setName(displayName);
            }
            if (marketplace.getUsername() == null || marketplace.getUsername().isBlank()) {
                marketplace.setUsername(allocateUsername(
                        marketplace.getName() != null ? marketplace.getName() : displayName, phone));
            }
            if (!MarketplaceSupplierStatuses.ACTIVE.equalsIgnoreCase(marketplace.getStatus())
                    && !MarketplaceSupplierStatuses.SUSPENDED.equalsIgnoreCase(marketplace.getStatus())) {
                marketplace.setStatus(MarketplaceSupplierStatuses.ACTIVE);
            }
            marketplaceSupplierRepository.saveAndFlush(marketplace);
        }
        passportService.ensureNumberAndIndex(marketplace);

        // Heal placeholder passport names from any linked local shop supplier.
        String localPreferred = suggestName(phone);
        if (!MarketplaceSupplierNaming.isPlaceholderName(localPreferred)) {
            passportService.upgradeNameIfPlaceholder(marketplace, localPreferred);
        }

        SupplierUser user = new SupplierUser();
        user.setMarketplaceSupplierId(marketplace.getId());
        user.setPhone(phone);
        user.setEmail(email);
        user.setName(MarketplaceSupplierNaming.preferDisplayName(displayName, marketplace.getName()));
        user.setPasswordHash(secretHash);
        user.setRoleKey(SupplierUserRoles.ADMIN);
        user.setActive(true);
        user.setLastLoginAt(now);
        supplierUserRepository.saveAndFlush(user);

        challenge.setConsumedAt(now);
        verificationRepository.saveAndFlush(challenge);
        return user;
    }

    private java.util.Optional<MarketplaceSupplier> findReusableMarketplaceByPhone(String phone) {
        // Only reuse if no portal user already owns that passport.
        var byPhone = marketplaceSupplierRepository.findFirstByContactPhoneOrderByCreatedAtAsc(phone);
        if (byPhone.isPresent()) {
            String id = byPhone.get().getId();
            if (supplierUserRepository.findByMarketplaceSupplierIdOrderByCreatedAtAsc(id).isEmpty()) {
                return byPhone;
            }
        }
        for (SupplierIdentityIndex row : identityIndexRepository.findMarketplaceByPhone(phone)) {
            if (row.getMarketplaceSupplierId() == null) {
                continue;
            }
            if (!supplierUserRepository.findByMarketplaceSupplierIdOrderByCreatedAtAsc(
                    row.getMarketplaceSupplierId()).isEmpty()) {
                continue;
            }
            return marketplaceSupplierRepository.findById(row.getMarketplaceSupplierId());
        }
        return java.util.Optional.empty();
    }

    private SupplierPortalLoginResponse loginResponse(
            SupplierUser user,
            boolean autoLogin,
            HttpServletRequest http
    ) {
        if (!autoLogin) {
            return sessionService.loginWithoutToken(user);
        }
        try {
            return sessionService.issueLogin(user, http);
        } catch (RuntimeException ex) {
            // Account already committed — never fail claim because auto-login broke.
            log.error(
                    "supplier portal auto-login after claim failed for user {}: {}",
                    user.getId(),
                    ex.getMessage(),
                    ex);
            return sessionService.loginWithoutToken(user);
        }
    }

    private void publishClaimedBestEffort(
            String marketplaceSupplierId,
            String userId,
            String phone,
            String path
    ) {
        try {
            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("marketplaceSupplierId", marketplaceSupplierId);
            diff.put("userId", userId);
            diff.put("phone", phone);
            diff.put("path", path);
            // Platform-scoped event: audit_events.business_id is NOT NULL — use sentinel.
            auditEventPublisher.publish(auditEventBuilder
                    .builder(AuditEventCategory.SUPPLIERS, AuditEventTypes.SUPPLIER_CLAIMED_ACCOUNT, AuditEventSeverity.INFO)
                    .businessId("platform")
                    .actor(userId, AuditEventActorType.USER)
                    .target("marketplace_supplier", marketplaceSupplierId)
                    .source("supplier_portal")
                    .diff(diff)
                    .build());
        } catch (RuntimeException ex) {
            log.warn("supplier portal claim audit skipped: {}", rootMessage(ex));
        }
    }

    private void upsertMarketplaceIdentityBestEffort(String marketplaceSupplierId) {
        if (marketplaceSupplierId == null || marketplaceSupplierId.isBlank()) {
            return;
        }
        try {
            MarketplaceSupplier marketplace = marketplaceSupplierRepository.findById(marketplaceSupplierId)
                    .orElse(null);
            if (marketplace == null) {
                return;
            }
            passportService.ensureNumberAndIndex(marketplace);
        } catch (RuntimeException ex) {
            // Index is searchable convenience — never abort account creation.
            log.warn(
                    "supplier identity index upsert skipped for {}: {}",
                    marketplaceSupplierId,
                    rootMessage(ex));
        }
    }

    private void linkLocalsBestEffort(String marketplaceSupplierId, String phone) {
        if (marketplaceSupplierId == null) {
            return;
        }
        try {
            // Prefer the heal service — matches phone variants + already-tagged locals
            // and imports shop-linked products into the portal catalogue.
            shopLinkService.ensureLinksAndCatalogue(marketplaceSupplierId);
        } catch (RuntimeException ex) {
            log.warn(
                    "supplier portal auto-link shops skipped for {}: {}",
                    marketplaceSupplierId,
                    rootMessage(ex));
            // Fallback to legacy phone-index link only.
            if (phone != null && !phone.isBlank()) {
                try {
                    linkLocalsByPhone(marketplaceSupplierId, phone);
                } catch (RuntimeException ignored) {
                    // Already logged above.
                }
            }
        }
    }

    private void linkLocalsByPhone(String marketplaceSupplierId, String phone) {
        List<SupplierIdentityIndex> hits = identityIndexRepository.findTenantByPhone(phone);
        for (SupplierIdentityIndex row : hits) {
            try {
                linkOneLocal(marketplaceSupplierId, phone, row);
            } catch (RuntimeException ex) {
                log.warn(
                        "supplier portal auto-link skipped for local {}: {}",
                        row.getSupplierId(),
                        rootMessage(ex));
            }
        }
    }

    private void linkOneLocal(String marketplaceSupplierId, String phone, SupplierIdentityIndex row) {
        if (row.getSupplierId() == null || row.getBusinessId() == null) {
            return;
        }
        // uq_bsc_local_supplier is unique regardless of status — skip any existing row.
        if (connectionRepository.existsByLocalSupplierId(row.getSupplierId())) {
            return;
        }
        Supplier local = supplierRepository.findByIdAndDeletedAtIsNull(row.getSupplierId()).orElse(null);
        if (local == null) {
            return;
        }
        if (local.getMarketplaceSupplierId() != null
                && !local.getMarketplaceSupplierId().equals(marketplaceSupplierId)) {
            return;
        }
        if (connectionRepository.existsByBusinessIdAndMarketplaceSupplierId(
                local.getBusinessId(), marketplaceSupplierId)) {
            return;
        }
        BusinessSupplierConnection connection = new BusinessSupplierConnection();
        connection.setBusinessId(local.getBusinessId());
        connection.setMarketplaceSupplierId(marketplaceSupplierId);
        connection.setLocalSupplierId(local.getId());
        connection.setStatus(BusinessSupplierConnectionStatuses.ACTIVE);
        connection.setCanViewPurchaseHistory(true);
        connectionRepository.saveAndFlush(connection);
        local.setMarketplaceSupplierId(marketplaceSupplierId);
        supplierRepository.save(local);
        try {
            identityIndexService.upsertTenantSupplier(local, phone, null);
        } catch (RuntimeException ex) {
            log.warn("tenant identity index upsert skipped for {}: {}", local.getId(), rootMessage(ex));
        }
    }

    private static ResponseStatusException mapClaimIntegrityViolation(DataIntegrityViolationException ex) {
        String flat = flattenMessages(ex).toLowerCase();
        if (flat.contains("uq_supplier_users_phone") || (flat.contains("supplier_users") && flat.contains("phone"))) {
            return new ResponseStatusException(HttpStatus.CONFLICT, "This phone already has an account — sign in");
        }
        if (flat.contains("uq_supplier_users_email") || (flat.contains("supplier_users") && flat.contains("email"))) {
            return new ResponseStatusException(HttpStatus.CONFLICT, "That email is already in use");
        }
        if (flat.contains("uq_marketplace_suppliers_username") || flat.contains("username")) {
            return new ResponseStatusException(HttpStatus.CONFLICT, "Username is taken");
        }
        if (flat.contains("business_supplier_connections") || flat.contains("uq_bsc_")) {
            // Should be best-effort outside TX now; keep a clear client message if it races in.
            return new ResponseStatusException(
                    HttpStatus.CONFLICT, "Shop link conflict — account may already exist; try signing in");
        }
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Could not finish account setup. If you already registered, sign in instead.");
    }

    private static ResponseStatusException mapClaimDataAccess(DataAccessException ex) {
        String flat = flattenMessages(ex).toLowerCase();
        if (flat.contains("supplier_user_sessions")
                || flat.contains("supplier_phone_verifications")
                || flat.contains("supplier_portal_claim")
                || flat.contains("marketplace_suppliers")
                || flat.contains("supplier_users")
                || flat.contains("unknown column")
                || flat.contains("doesn't exist")) {
            return new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Database is missing a Supplier Portal migration (V169–V172). Redeploy the API and retry.");
        }
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Could not finish account setup. If you already registered, sign in instead.");
    }

    private static ResponseStatusException mapClaimTransactionFailure(TransactionException ex) {
        Throwable root = ex.getMostSpecificCause() != null ? ex.getMostSpecificCause() : ex;
        if (root instanceof ResponseStatusException rse) {
            return rse;
        }
        if (root instanceof DataIntegrityViolationException dive) {
            return mapClaimIntegrityViolation(dive);
        }
        if (root instanceof DataAccessException dae) {
            return mapClaimDataAccess(dae);
        }
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Could not finish account setup. If you already registered, sign in instead.");
    }

    private static ResponseStatusException findResponseStatus(Throwable ex) {
        Throwable cur = ex;
        int depth = 0;
        while (cur != null && depth < 8) {
            if (cur instanceof ResponseStatusException rse) {
                return rse;
            }
            cur = cur.getCause();
            depth++;
        }
        return null;
    }

    private static String rootMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : ex.getMessage();
    }

    private static String flattenMessages(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = ex;
        int depth = 0;
        while (cur != null && depth < 8) {
            if (cur.getMessage() != null) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(cur.getMessage());
            }
            cur = cur.getCause();
            depth++;
        }
        return sb.toString();
    }

    private String allocateUsername(String displayName, String phone) {
        String base = SupplierSlug.slugify(displayName);
        if (base.length() < 2) {
            base = "s" + phone.substring(Math.max(0, phone.length() - 8));
        }
        if (base.length() > 60) {
            base = base.substring(0, 60).replaceAll("-+$", "");
        }
        String candidate = base;
        int i = 0;
        while (marketplaceSupplierRepository.existsByUsernameIgnoreCase(candidate)) {
            i += 1;
            String suffix = "-" + i;
            candidate = base.substring(0, Math.min(base.length(), 64 - suffix.length())) + suffix;
            if (i > 50) {
                suffix = "-" + UUID.randomUUID().toString().substring(0, 6);
                candidate = base.substring(0, Math.min(base.length(), 64 - suffix.length())) + suffix;
                break;
            }
        }
        return candidate;
    }

    private String suggestName(String phone) {
        String fromIndex = identityIndexRepository.findTenantByPhone(phone).stream()
                .map(SupplierIdentityIndex::getSupplierId)
                .filter(id -> id != null && !id.isBlank())
                .map(id -> supplierRepository.findByIdAndDeletedAtIsNull(id).orElse(null))
                .filter(s -> s != null && s.getName() != null && !s.getName().isBlank())
                .map(Supplier::getName)
                .filter(name -> !MarketplaceSupplierNaming.isPlaceholderName(name))
                .findFirst()
                .orElse(null);
        if (fromIndex != null) {
            return fromIndex;
        }
        // Broader phone variants (0… vs 254…)
        String alt = phone.startsWith("254") && phone.length() == 12
                ? "0" + phone.substring(3)
                : (phone.startsWith("0") && phone.length() == 10 ? "254" + phone.substring(1) : phone);
        String tail = phone.length() >= 9 ? phone.substring(phone.length() - 9) : phone;
        String fromVariants = identityIndexRepository.findTenantByPhoneVariants(phone, alt, tail).stream()
                .map(SupplierIdentityIndex::getSupplierId)
                .filter(id -> id != null && !id.isBlank())
                .map(id -> supplierRepository.findByIdAndDeletedAtIsNull(id).orElse(null))
                .filter(s -> s != null && s.getName() != null && !s.getName().isBlank())
                .map(Supplier::getName)
                .filter(name -> !MarketplaceSupplierNaming.isPlaceholderName(name))
                .findFirst()
                .orElse(null);
        if (fromVariants != null) {
            return fromVariants;
        }
        return MarketplaceSupplierNaming.placeholderFromPhone(phone);
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

    private String encodeUnlockSecret(SupplierPortalClaimCompleteRequest request) {
        String pin = blankToNull(request.pin());
        String password = blankToNull(request.password());
        if (pin != null) {
            if (!pin.matches("\\d{4,6}")) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "PIN must be 4 to 6 digits");
            }
            if (password != null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Choose a PIN or a password, not both");
            }
            return passwordEncoder.encode(pin);
        }
        portalSettingsService.validatePassword(password);
        return passwordEncoder.encode(password);
    }

    private void applyDeskCard(
            MarketplaceSupplier marketplace,
            SupplierPortalClaimCompleteRequest request,
            String verifiedPhone
    ) {
        String altRaw = blankToNull(request.altPhone());
        if (altRaw != null) {
            String alt = StkPhoneNormalizer.normalize(altRaw);
            if (alt == null || alt.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Enter a valid extra WhatsApp number");
            }
            if (!alt.equals(verifiedPhone)) {
                if (marketplace.getAltContactPhone() == null || marketplace.getAltContactPhone().isBlank()) {
                    marketplace.setAltContactPhone(alt);
                }
            }
        }
        String location = blankToNull(request.location());
        if (location != null
                && (marketplace.getContactLocation() == null || marketplace.getContactLocation().isBlank())) {
            marketplace.setContactLocation(location);
        }
        String person = blankToNull(request.name());
        if (person != null
                && (marketplace.getContactPerson() == null || marketplace.getContactPerson().isBlank())) {
            marketplace.setContactPerson(person);
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
