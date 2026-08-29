package zelisline.ub.credits.application;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.api.dto.SendCustomerPhoneVerificationResponse;
import zelisline.ub.credits.api.dto.VerifyCustomerPhoneVerificationResponse;
import zelisline.ub.credits.domain.CustomerPhoneNormalizer;
import zelisline.ub.credits.domain.CustomerPhoneVerification;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerPhoneVerificationRepository;
import zelisline.ub.identity.application.TokenHasher;
import zelisline.ub.messaging.application.CustomerMessageDispatcher;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.messaging.domain.SmsSendReason;

@Service
@RequiredArgsConstructor
public class CustomerPhoneVerificationService {

    static final Duration OTP_TTL = Duration.ofMinutes(10);
    static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    static final Duration REGISTRATION_TOKEN_TTL = Duration.ofMinutes(15);
    static final int MAX_ATTEMPTS = 5;
    static final int OTP_DIGITS = 4;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final CustomerPhoneVerificationRepository verificationRepository;
    private final CustomerPhoneRepository customerPhoneRepository;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final CustomerMessageDispatcher customerMessageDispatcher;

    @Transactional
    public SendCustomerPhoneVerificationResponse send(String businessId, String rawPhone) {
        return send(businessId, rawPhone, null);
    }

    @Transactional
    public SendCustomerPhoneVerificationResponse sendForOwner(
            String businessId,
            String rawPhone,
            String ownerCustomerId
    ) {
        return send(businessId, rawPhone, ownerCustomerId);
    }

    @Transactional
    public SendCustomerPhoneVerificationResponse sendForShopper(String businessId, String rawPhone) {
        return send(businessId, rawPhone, "shopper");
    }

    private SendCustomerPhoneVerificationResponse send(
            String businessId,
            String rawPhone,
            String ownerCustomerId
    ) {
        String phone = normalizeOrThrow(rawPhone);
        if (!"shopper".equals(ownerCustomerId)) {
            customerPhoneRepository.findFirstByBusinessIdAndPhone(businessId, phone).ifPresent(existing -> {
                if (ownerCustomerId == null || !ownerCustomerId.equals(existing.getCustomerId())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Phone already in use for this business");
                }
            });
        }

        Instant now = Instant.now();
        verificationRepository.findFirstByBusinessIdAndPhoneAndConsumedAtIsNullOrderByCreatedAtDesc(businessId, phone)
                .ifPresent(open -> {
                    if (open.getLastSentAt() != null
                            && open.getLastSentAt().plus(RESEND_COOLDOWN).isAfter(now)
                            && open.getVerifiedAt() == null) {
                        throw new ResponseStatusException(
                                HttpStatus.TOO_MANY_REQUESTS,
                                "Wait before requesting another code");
                    }
                });

        for (CustomerPhoneVerification open :
                verificationRepository.findByBusinessIdAndPhoneAndConsumedAtIsNull(businessId, phone)) {
            open.setConsumedAt(now);
            verificationRepository.save(open);
        }

        String code = generateOtp();
        CustomerPhoneVerification challenge = new CustomerPhoneVerification();
        challenge.setBusinessId(businessId);
        challenge.setPhone(phone);
        challenge.setCodeHash(TokenHasher.sha256Hex(code));
        challenge.setExpiresAt(now.plus(OTP_TTL));
        challenge.setAttempts(0);
        challenge.setMaxAttempts(MAX_ATTEMPTS);
        challenge.setLastSentAt(now);
        verificationRepository.save(challenge);

        TenantMessagingConfig messaging = messagingSettingsService.resolveForTest(businessId, SmsSendReason.OTP);
        if (!messaging.secretsReadable()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    messaging.secretsReadError() != null
                            ? messaging.secretsReadError()
                            : "Messaging credentials are not readable");
        }
        if (!messaging.metaWhatsAppConfigured() && !messaging.smsConfigured()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "WhatsApp or SMS must be configured to send a verification code");
        }
        if (!messaging.smsConfigured()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "SMS must be configured to send verification codes. Set Sozuri or TextSMS under"
                            + " Super Admin → Platform integrations, or under Customers → messaging settings.");
        }

        String message = "Your Palmart verification code is " + code + ". Valid for 10 minutes.";
        CustomerMessageDispatcher.DeliveryResult delivery =
                customerMessageDispatcher.deliverBothChannels(messaging, phone, message);
        if (!"sent".equals(delivery.outcome()) && !"stub".equals(delivery.outcome())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    CustomerMessageDispatcher.verificationFailureMessage(delivery));
        }

        return new SendCustomerPhoneVerificationResponse(
                phone,
                challenge.getExpiresAt(),
                delivery.channel(),
                maskPhone(phone));
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public VerifyCustomerPhoneVerificationResponse verify(String businessId, String rawPhone, String rawCode) {
        String phone = normalizeOrThrow(rawPhone);
        String code = rawCode == null ? "" : rawCode.trim();
        if (!code.matches("\\d{" + OTP_DIGITS + "}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the 4-digit verification code");
        }

        CustomerPhoneVerification challenge = verificationRepository
                .findFirstByBusinessIdAndPhoneAndConsumedAtIsNullOrderByCreatedAtDesc(businessId, phone)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "No active verification for this phone — send a code first"));

        Instant now = Instant.now();
        if (challenge.getExpiresAt().isBefore(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Verification code expired — send a new one");
        }
        if (challenge.getAttempts() >= challenge.getMaxAttempts()) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "Too many incorrect attempts — send a new code");
        }

        String expectedHash = challenge.getCodeHash();
        String suppliedHash = TokenHasher.sha256Hex(code);
        if (!constantTimeEquals(expectedHash, suppliedHash)) {
            challenge.setAttempts(challenge.getAttempts() + 1);
            verificationRepository.save(challenge);
            if (challenge.getAttempts() >= challenge.getMaxAttempts()) {
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS, "Too many incorrect attempts — send a new code");
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Incorrect verification code");
        }

        String registrationToken = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        Instant tokenExpires = now.plus(REGISTRATION_TOKEN_TTL);
        challenge.setVerifiedAt(now);
        challenge.setRegistrationTokenHash(TokenHasher.sha256Hex(registrationToken));
        challenge.setRegistrationTokenExpiresAt(tokenExpires);
        verificationRepository.save(challenge);

        return new VerifyCustomerPhoneVerificationResponse(registrationToken, tokenExpires);
    }

    /**
     * Validates and consumes a registration token issued by {@link #verify}.
     * Returns the normalized phone the token was issued for.
     */
    @Transactional
    public String consumeRegistrationToken(String businessId, String rawToken, String expectedPhone) {
        String token = rawToken == null ? "" : rawToken.trim();
        if (token.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone verification token required");
        }
        String phone = normalizeOrThrow(expectedPhone);
        String tokenHash = TokenHasher.sha256Hex(token);
        CustomerPhoneVerification challenge = verificationRepository
                .findFirstByBusinessIdAndRegistrationTokenHashAndConsumedAtIsNull(businessId, tokenHash)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Invalid or already used phone verification token"));

        Instant now = Instant.now();
        if (challenge.getVerifiedAt() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone is not verified");
        }
        if (challenge.getRegistrationTokenExpiresAt() == null
                || challenge.getRegistrationTokenExpiresAt().isBefore(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone verification token expired");
        }
        if (!phone.equals(challenge.getPhone())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Phone verification token does not match this phone");
        }

        challenge.setConsumedAt(now);
        verificationRepository.save(challenge);
        return phone;
    }

    private static String generateOtp() {
        int bound = (int) Math.pow(10, OTP_DIGITS);
        int value = SECURE_RANDOM.nextInt(bound);
        return String.format("%0" + OTP_DIGITS + "d", value);
    }

    private static String normalizeOrThrow(String raw) {
        String n = CustomerPhoneNormalizer.normalize(raw);
        if (n.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone must contain digits");
        }
        return n;
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
}
