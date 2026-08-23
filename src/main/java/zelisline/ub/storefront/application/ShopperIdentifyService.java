package zelisline.ub.storefront.application;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.api.dto.SendCustomerPhoneVerificationResponse;
import zelisline.ub.credits.api.dto.VerifyCustomerPhoneVerificationResponse;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.credits.application.CustomerPhoneVerificationService;
import zelisline.ub.credits.domain.CustomerPhoneNormalizer;
import zelisline.ub.credits.domain.CustomerPhoneVerification;
import zelisline.ub.credits.domain.KenyanPhoneForms;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerPhoneVerificationRepository;
import zelisline.ub.identity.application.TokenHasher;
import zelisline.ub.messaging.application.CustomerMessageDispatcher;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.tenancy.api.dto.PublicShopsSearchResponse;
import zelisline.ub.tenancy.application.PublicShopsSearchService;

/**
 * Phase 4 apex "one door": tenant-agnostic shopper identification (the doc's
 * "global lookup" — §8, §13, §22). The apex never authenticates; it verifies a
 * phone ONCE platform-wide and returns the shops that phone has a customer
 * record in, so the apex can forward to the right shop host.
 *
 * <p>Verification reuses the existing per-business {@code CustomerPhoneVerification}
 * machinery under a sentinel {@code "platform"} tenant id, and delivers the OTP
 * through the platform's own messaging credentials
 * ({@code resolvePlatformForContactReply}) — no tenant business settings needed.
 *
 * <p>Privacy (§12): an unknown number's verify still succeeds (OTP proves only
 * "you own this phone"); the shops lookup simply returns an empty list, so
 * phone existence is never confirmed without a code the caller controls.
 */
@Service
@RequiredArgsConstructor
public class ShopperIdentifyService {

    /** Sentinel tenant id for platform-scoped (tenant-agnostic) phone verification. */
    static final String PLATFORM_VERIFICATION_BUSINESS_ID = "platform";

    static final Duration OTP_TTL = Duration.ofMinutes(10);
    static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    static final int OTP_DIGITS = 4;
    static final int MAX_ATTEMPTS = 5;
    static final int MAX_SHOPS = 8;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final CustomerPhoneVerificationRepository verificationRepository;
    private final CustomerPhoneRepository customerPhoneRepository;
    private final CustomerPhoneVerificationService phoneVerificationService;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final CustomerMessageDispatcher customerMessageDispatcher;
    private final PublicShopsSearchService publicShopsSearchService;

    @Transactional
    public SendCustomerPhoneVerificationResponse sendCode(String rawPhone) {
        String phone = normalizeOrThrow(rawPhone);
        Instant now = Instant.now();

        verificationRepository
                .findFirstByBusinessIdAndPhoneAndConsumedAtIsNullOrderByCreatedAtDesc(
                        PLATFORM_VERIFICATION_BUSINESS_ID, phone)
                .ifPresent(open -> {
                    if (open.getLastSentAt() != null
                            && open.getLastSentAt().plus(RESEND_COOLDOWN).isAfter(now)
                            && open.getVerifiedAt() == null) {
                        throw new ResponseStatusException(
                                HttpStatus.TOO_MANY_REQUESTS,
                                "Wait before requesting another code");
                    }
                });

        for (CustomerPhoneVerification open : verificationRepository
                .findByBusinessIdAndPhoneAndConsumedAtIsNull(PLATFORM_VERIFICATION_BUSINESS_ID, phone)) {
            open.setConsumedAt(now);
            verificationRepository.save(open);
        }

        String code = generateOtp();
        CustomerPhoneVerification challenge = new CustomerPhoneVerification();
        challenge.setBusinessId(PLATFORM_VERIFICATION_BUSINESS_ID);
        challenge.setPhone(phone);
        challenge.setCodeHash(TokenHasher.sha256Hex(code));
        challenge.setExpiresAt(now.plus(OTP_TTL));
        challenge.setAttempts(0);
        challenge.setMaxAttempts(MAX_ATTEMPTS);
        challenge.setLastSentAt(now);
        verificationRepository.save(challenge);

        TenantMessagingConfig messaging = messagingSettingsService.resolvePlatformForContactReply();
        if (!messaging.secretsReadable()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Messaging credentials are not readable");
        }
        if (!messaging.metaWhatsAppConfigured() && !messaging.smsConfigured()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "WhatsApp or SMS must be configured to send a verification code");
        }

        String message = "Your Kiosk verification code is " + code + ". Valid for 10 minutes.";
        CustomerMessageDispatcher.DeliveryResult delivery =
                customerMessageDispatcher.deliverBothChannels(messaging, phone, message);
        if (!"sent".equals(delivery.outcome()) && !"stub".equals(delivery.outcome())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not send verification code (" + delivery.channel() + ")");
        }

        return new SendCustomerPhoneVerificationResponse(
                phone, challenge.getExpiresAt(), delivery.channel(), maskPhone(phone));
    }

    @Transactional
    public VerifyCustomerPhoneVerificationResponse verifyCode(String rawPhone, String code) {
        // Reuses the per-business challenge lifecycle (TTL, attempts, token minting)
        // keyed by the platform sentinel — the implementation is tenant-key agnostic.
        return phoneVerificationService.verify(PLATFORM_VERIFICATION_BUSINESS_ID, rawPhone, code);
    }

    /**
     * Consumes the platform verification token, then returns the shops the phone
     * has a customer record in. Empty for a verified phone with no history —
     * never an error, so existence is never confirmed (§12).
     */
    @Transactional
    public List<PublicShopsSearchResponse> shops(String rawPhone, String rawToken) {
        String phone = phoneVerificationService.consumeRegistrationToken(
                PLATFORM_VERIFICATION_BUSINESS_ID, rawToken, rawPhone);
        List<String> businessIds = customerPhoneRepository
                .findDistinctBusinessIdByPhones(KenyanPhoneForms.lookupCandidates(phone));
        if (businessIds.isEmpty()) {
            return List.of();
        }
        return publicShopsSearchService.byBusinessIds(businessIds);
    }

    private static String normalizeOrThrow(String raw) {
        String normalized = CustomerPhoneNormalizer.normalize(raw);
        if (normalized.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone must contain digits");
        }
        return normalized;
    }

    private static String generateOtp() {
        int bound = (int) Math.pow(10, OTP_DIGITS);
        int value = SECURE_RANDOM.nextInt(bound);
        return String.format("%0" + OTP_DIGITS + "d", value);
    }

    private static String maskPhone(String phone) {
        if (phone.length() <= 4) {
            return "****";
        }
        return "*".repeat(Math.min(phone.length() - 4, 8)) + phone.substring(phone.length() - 4);
    }
}
