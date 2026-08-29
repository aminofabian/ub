package zelisline.ub.opsalerts.application;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.identity.application.TokenHasher;
import zelisline.ub.messaging.application.CustomerMessageDispatcher;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.messaging.domain.SmsSendReason;
import zelisline.ub.opsalerts.api.dto.SendOpsAlertPhoneVerificationResponse;
import zelisline.ub.opsalerts.api.dto.VerifyOpsAlertPhoneVerificationResponse;
import zelisline.ub.opsalerts.domain.BusinessOpsAlertSettings;
import zelisline.ub.opsalerts.domain.OpsAlertPhoneVerification;
import zelisline.ub.opsalerts.repository.OpsAlertPhoneVerificationRepository;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class OpsAlertPhoneVerificationService {

    private static final Logger log = LoggerFactory.getLogger(OpsAlertPhoneVerificationService.class);

    static final Duration OTP_TTL = Duration.ofMinutes(10);
    static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    static final int MAX_ATTEMPTS = 5;
    static final int OTP_DIGITS = 4;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final OpsAlertPhoneVerificationRepository verificationRepository;
    private final BusinessOpsAlertSettingsService settingsService;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final CustomerMessageDispatcher customerMessageDispatcher;
    private final BusinessRepository businessRepository;

    @Transactional
    public SendOpsAlertPhoneVerificationResponse send(String businessId, String rawPhone) {
        String phone = normalizeOrThrow(rawPhone);

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

        for (OpsAlertPhoneVerification open :
                verificationRepository.findByBusinessIdAndPhoneAndConsumedAtIsNull(businessId, phone)) {
            open.setConsumedAt(now);
            verificationRepository.save(open);
        }

        String code = generateOtp();
        OpsAlertPhoneVerification challenge = new OpsAlertPhoneVerification();
        challenge.setBusinessId(businessId);
        challenge.setPhone(phone);
        challenge.setCodeHash(TokenHasher.sha256Hex(code));
        challenge.setExpiresAt(now.plus(OTP_TTL));
        challenge.setAttempts(0);
        challenge.setMaxAttempts(MAX_ATTEMPTS);
        challenge.setLastSentAt(now);
        verificationRepository.save(challenge);

        // Same resolve path as customer OTP / ops alert dispatch: tenant SMS first,
        // then platform integrations — not platform-only (which skipped working tenant TextSMS).
        TenantMessagingConfig messaging =
                messagingSettingsService.resolveForTest(businessId, SmsSendReason.OTP);
        if (!messaging.secretsReadable()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    messaging.secretsReadError() != null
                            ? messaging.secretsReadError()
                            : "Messaging credentials are not readable");
        }
        if (!messaging.smsConfigured()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    messaging.smsNotConfiguredHint());
        }

        String shopName = resolveShopName(businessId);
        String message = "Your " + shopName + " alert verification code is " + code
                + ". Valid for 10 minutes. Do not share this code.";
        CustomerMessageDispatcher.DeliveryResult delivery =
                customerMessageDispatcher.deliverBothChannels(messaging, phone, message);
        if (!"sent".equals(delivery.outcome()) && !"stub".equals(delivery.outcome())) {
            log.warn(
                    "Ops alert OTP not sent business={} phone={} channel={} detail={}",
                    businessId,
                    BusinessOpsAlertSettingsService.maskPhone(phone),
                    delivery.channel(),
                    delivery.detail());
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    CustomerMessageDispatcher.verificationFailureMessage(delivery));
        }

        return new SendOpsAlertPhoneVerificationResponse(
                phone,
                challenge.getExpiresAt(),
                delivery.channel(),
                BusinessOpsAlertSettingsService.maskPhone(phone));
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public VerifyOpsAlertPhoneVerificationResponse verify(String businessId, String rawPhone, String rawCode) {
        String phone = normalizeOrThrow(rawPhone);
        String code = rawCode == null ? "" : rawCode.trim();
        if (!code.matches("\\d{" + OTP_DIGITS + "}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the 4-digit verification code");
        }

        OpsAlertPhoneVerification challenge = verificationRepository
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

        challenge.setVerifiedAt(now);
        challenge.setConsumedAt(now);
        verificationRepository.save(challenge);

        BusinessOpsAlertSettings saved = settingsService.assignVerifiedPhone(businessId, phone);
        return new VerifyOpsAlertPhoneVerificationResponse(
                phone,
                BusinessOpsAlertSettingsService.maskPhone(phone),
                saved.getPhoneVerifiedAt());
    }

    private String resolveShopName(String businessId) {
        return businessRepository.findById(businessId)
                .map(Business::getName)
                .filter(n -> n != null && !n.isBlank())
                .orElse("Palmart");
    }

    private static String generateOtp() {
        int bound = (int) Math.pow(10, OTP_DIGITS);
        int value = SECURE_RANDOM.nextInt(bound);
        return String.format("%0" + OTP_DIGITS + "d", value);
    }

    /**
     * Kenyan MSISDN as {@code 2547XXXXXXXX} so SMS providers get {@code +2547…},
     * not invalid {@code +07…} from digit-only stripping of local numbers.
     */
    private static String normalizeOrThrow(String raw) {
        String n = StkPhoneNormalizer.normalize(raw);
        if (n == null || n.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Enter a valid Kenyan mobile number (e.g. 07XX XXX XXX or +254…)");
        }
        return n;
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
