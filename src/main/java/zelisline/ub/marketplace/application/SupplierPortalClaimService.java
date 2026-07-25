package zelisline.ub.marketplace.application;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.identity.application.TokenHasher;
import zelisline.ub.marketplace.api.dto.SupplierPortalClaimCompleteRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalClaimSendCodeResponse;
import zelisline.ub.marketplace.api.dto.SupplierPortalClaimVerifyCodeResponse;
import zelisline.ub.marketplace.api.dto.SupplierPortalLoginResponse;
import zelisline.ub.marketplace.domain.BusinessSupplierConnection;
import zelisline.ub.marketplace.domain.BusinessSupplierConnectionStatuses;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierStatuses;
import zelisline.ub.marketplace.domain.SupplierIdentityIndex;
import zelisline.ub.marketplace.domain.SupplierPhoneVerification;
import zelisline.ub.marketplace.domain.SupplierUser;
import zelisline.ub.marketplace.domain.SupplierUserRoles;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.marketplace.repository.SupplierIdentityIndexRepository;
import zelisline.ub.marketplace.repository.SupplierPhoneVerificationRepository;
import zelisline.ub.marketplace.repository.SupplierUserRepository;
import zelisline.ub.messaging.application.CustomerMessageDispatcher;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.platform.security.JwtTokenService;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierSlug;
import zelisline.ub.suppliers.repository.SupplierRepository;

@Service
@RequiredArgsConstructor
public class SupplierPortalClaimService {

    static final Duration OTP_TTL = Duration.ofMinutes(10);
    static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    static final Duration SETUP_TOKEN_TTL = Duration.ofMinutes(15);
    static final int MAX_ATTEMPTS = 5;
    static final int OTP_DIGITS = 4;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SupplierPhoneVerificationRepository verificationRepository;
    private final SupplierUserRepository supplierUserRepository;
    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final SupplierIdentityIndexRepository identityIndexRepository;
    private final SupplierRepository supplierRepository;
    private final BusinessSupplierConnectionRepository connectionRepository;
    private final SupplierIdentityIndexService identityIndexService;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final CustomerMessageDispatcher customerMessageDispatcher;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;

    @Transactional
    public SupplierPortalClaimSendCodeResponse sendCode(String rawPhone) {
        String phone = normalizePhoneOrThrow(rawPhone);
        if (supplierUserRepository.existsByPhone(phone)) {
            return new SupplierPortalClaimSendCodeResponse(
                    phone, maskPhone(phone), null, null, true);
        }

        Instant now = Instant.now();
        verificationRepository.findFirstByPhoneAndConsumedAtIsNullOrderByCreatedAtDesc(phone)
                .ifPresent(open -> {
                    if (open.getLastSentAt() != null
                            && open.getLastSentAt().plus(RESEND_COOLDOWN).isAfter(now)
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

        String code = generateOtp();
        SupplierPhoneVerification challenge = new SupplierPhoneVerification();
        challenge.setPhone(phone);
        challenge.setCodeHash(TokenHasher.sha256Hex(code));
        challenge.setExpiresAt(now.plus(OTP_TTL));
        challenge.setAttempts(0);
        challenge.setMaxAttempts(MAX_ATTEMPTS);
        challenge.setLastSentAt(now);
        verificationRepository.save(challenge);

        TenantMessagingConfig messaging = messagingSettingsService.resolvePlatformForContactReply();
        if (!messaging.enabled() || (!messaging.smsConfigured() && !messaging.metaWhatsAppConfigured())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Messaging is not configured to send a verification code");
        }

        String message = "Your Kiosk supplier code is " + code + ". Valid for 10 minutes.";
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
        String phone = normalizePhoneOrThrow(rawPhone);
        String code = rawCode == null ? "" : rawCode.trim();
        if (!code.matches("\\d{" + OTP_DIGITS + "}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the 4-digit verification code");
        }
        if (supplierUserRepository.existsByPhone(phone)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This phone already has an account — sign in");
        }

        SupplierPhoneVerification challenge = verificationRepository
                .findFirstByPhoneAndConsumedAtIsNullOrderByCreatedAtDesc(phone)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "No active verification — send a code first"));

        Instant now = Instant.now();
        if (challenge.getExpiresAt().isBefore(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Code expired — send a new one");
        }
        if (challenge.getAttempts() >= challenge.getMaxAttempts()) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "Too many incorrect attempts — send a new code");
        }

        if (!constantTimeEquals(challenge.getCodeHash(), TokenHasher.sha256Hex(code))) {
            challenge.setAttempts(challenge.getAttempts() + 1);
            verificationRepository.save(challenge);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Incorrect verification code");
        }

        String setupToken = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        Instant tokenExpires = now.plus(SETUP_TOKEN_TTL);
        challenge.setVerifiedAt(now);
        challenge.setSetupTokenHash(TokenHasher.sha256Hex(setupToken));
        challenge.setSetupTokenExpiresAt(tokenExpires);
        verificationRepository.save(challenge);

        return new SupplierPortalClaimVerifyCodeResponse(
                setupToken, tokenExpires, suggestName(phone));
    }

    @Transactional
    public SupplierPortalLoginResponse complete(SupplierPortalClaimCompleteRequest request) {
        String phone = normalizePhoneOrThrow(request.phone());
        if (supplierUserRepository.existsByPhone(phone)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This phone already has an account — sign in");
        }

        String token = request.setupToken() == null ? "" : request.setupToken().trim();
        SupplierPhoneVerification challenge = verificationRepository
                .findFirstBySetupTokenHashAndConsumedAtIsNull(TokenHasher.sha256Hex(token))
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

        String email = blankToNull(request.email());
        if (email != null) {
            email = email.trim().toLowerCase();
            if (!email.contains("@") || email.length() < 5) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid email");
            }
            if (supplierUserRepository.existsByEmail(email)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "That email is already in use");
            }
        }

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

        String jti = UUID.randomUUID().toString();
        String access = jwtTokenService.createSupplierAccessToken(
                user.getId(),
                user.getMarketplaceSupplierId(),
                user.getRoleKey(),
                jti);
        return new SupplierPortalLoginResponse(
                access,
                user.getId(),
                user.getMarketplaceSupplierId(),
                user.getEmail(),
                user.getPhone(),
                user.getName());
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

    private static String generateOtp() {
        int bound = (int) Math.pow(10, OTP_DIGITS);
        return String.format("%0" + OTP_DIGITS + "d", SECURE_RANDOM.nextInt(bound));
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
