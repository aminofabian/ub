package zelisline.ub.suppliers.application;

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
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.suppliers.api.dto.SendSupplierPayoutPhoneVerificationResponse;
import zelisline.ub.suppliers.api.dto.VerifySupplierPayoutPhoneResponse;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierPayoutPhoneVerification;
import zelisline.ub.suppliers.domain.SupplierPayoutTypes;
import zelisline.ub.suppliers.repository.SupplierPayoutPhoneVerificationRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * OTP-proves the supplier M-Pesa payout MSISDN before automated Send Money.
 */
@Service
@RequiredArgsConstructor
public class SupplierPayoutPhoneVerificationService {

    private static final Logger log = LoggerFactory.getLogger(SupplierPayoutPhoneVerificationService.class);

    static final Duration OTP_TTL = Duration.ofMinutes(10);
    static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    static final int MAX_ATTEMPTS = 5;
    static final int OTP_DIGITS = 4;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SupplierRepository supplierRepository;
    private final SupplierPayoutPhoneVerificationRepository verificationRepository;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final CustomerMessageDispatcher customerMessageDispatcher;
    private final BusinessRepository businessRepository;

    @Transactional
    public SendSupplierPayoutPhoneVerificationResponse send(String businessId, String supplierId) {
        Supplier supplier = requireSupplier(businessId, supplierId);
        if (!SupplierPayoutTypes.MOBILE_WALLET.equals(supplier.getPayoutType())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Switch payout type to M-Pesa phone before verifying");
        }
        String phone = supplier.getPayoutPhone();
        if (phone == null || phone.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Save an M-Pesa payout phone on the supplier first");
        }
        if (supplier.getPayoutPhoneVerifiedAt() != null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "This payout phone is already verified");
        }

        Instant now = Instant.now();
        verificationRepository
                .findFirstByBusinessIdAndSupplierIdAndPhoneAndConsumedAtIsNullOrderByCreatedAtDesc(
                        businessId, supplierId, phone)
                .ifPresent(open -> {
                    if (open.getLastSentAt() != null
                            && open.getLastSentAt().plus(RESEND_COOLDOWN).isAfter(now)
                            && open.getVerifiedAt() == null) {
                        throw new ResponseStatusException(
                                HttpStatus.TOO_MANY_REQUESTS,
                                "Wait before requesting another code");
                    }
                });

        for (SupplierPayoutPhoneVerification open :
                verificationRepository.findByBusinessIdAndSupplierIdAndPhoneAndConsumedAtIsNull(
                        businessId, supplierId, phone)) {
            open.setConsumedAt(now);
            verificationRepository.save(open);
        }

        String code = generateOtp();
        SupplierPayoutPhoneVerification challenge = new SupplierPayoutPhoneVerification();
        challenge.setBusinessId(businessId);
        challenge.setSupplierId(supplierId);
        challenge.setPhone(phone);
        challenge.setCodeHash(TokenHasher.sha256Hex(code));
        challenge.setExpiresAt(now.plus(OTP_TTL));
        challenge.setAttempts(0);
        challenge.setMaxAttempts(MAX_ATTEMPTS);
        challenge.setLastSentAt(now);
        verificationRepository.save(challenge);

        TenantMessagingConfig messaging = messagingSettingsService.resolveForOtp(businessId);
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
        String supplierName = supplier.getName() != null ? supplier.getName().trim() : "supplier";
        String message = "Your " + shopName + " code to confirm payout to " + supplierName + " is " + code
                + ". Valid for 10 minutes. Do not share this code.";
        CustomerMessageDispatcher.DeliveryResult delivery =
                customerMessageDispatcher.deliverBothChannels(messaging, phone, message);
        if (!"sent".equals(delivery.outcome()) && !"stub".equals(delivery.outcome())) {
            log.warn(
                    "Supplier payout OTP not sent business={} supplier={} phone={} detail={}",
                    businessId,
                    supplierId,
                    maskPhone(phone),
                    delivery.detail());
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    CustomerMessageDispatcher.verificationFailureMessage(delivery));
        }

        return new SendSupplierPayoutPhoneVerificationResponse(
                phone,
                challenge.getExpiresAt(),
                delivery.channel(),
                maskPhone(phone));
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public VerifySupplierPayoutPhoneResponse verify(String businessId, String supplierId, String rawCode) {
        Supplier supplier = requireSupplier(businessId, supplierId);
        if (!SupplierPayoutTypes.MOBILE_WALLET.equals(supplier.getPayoutType())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Switch payout type to M-Pesa phone before verifying");
        }
        String phone = supplier.getPayoutPhone();
        if (phone == null || phone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No payout phone on this supplier");
        }

        String code = rawCode == null ? "" : rawCode.trim();
        if (!code.matches("\\d{" + OTP_DIGITS + "}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the 4-digit verification code");
        }

        SupplierPayoutPhoneVerification challenge = verificationRepository
                .findFirstByBusinessIdAndSupplierIdAndPhoneAndConsumedAtIsNullOrderByCreatedAtDesc(
                        businessId, supplierId, phone)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "No active verification — send a code first"));

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

        supplier.setPayoutPhoneVerifiedAt(now);
        supplierRepository.save(supplier);

        return new VerifySupplierPayoutPhoneResponse(phone, maskPhone(phone), now);
    }

    /** Clear verification when destination changes (portal sync / patch). */
    public static void clearIfPhoneChanged(Supplier supplier, String previousPhone, String previousType) {
        String type = supplier.getPayoutType();
        String phone = supplier.getPayoutPhone();
        boolean stillWallet = SupplierPayoutTypes.MOBILE_WALLET.equals(type);
        boolean phoneSame = previousPhone != null && previousPhone.equals(phone);
        boolean typeSame = previousType != null && previousType.equals(type);
        if (!stillWallet || !phoneSame || !typeSame) {
            supplier.setPayoutPhoneVerifiedAt(null);
        }
    }

    private Supplier requireSupplier(String businessId, String supplierId) {
        return supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(supplierId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
    }

    private String resolveShopName(String businessId) {
        return businessRepository.findById(businessId)
                .map(Business::getName)
                .filter(n -> n != null && !n.isBlank())
                .orElse("Kiosk");
    }

    private static String generateOtp() {
        int bound = (int) Math.pow(10, OTP_DIGITS);
        int value = SECURE_RANDOM.nextInt(bound);
        return String.format("%0" + OTP_DIGITS + "d", value);
    }

    static String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) {
            return "••••";
        }
        return phone.substring(0, 4) + "••••" + phone.substring(phone.length() - 3);
    }

    /** Normalize for callers that pass raw input (portal sync). */
    public static String normalizePhoneOrNull(String raw) {
        return StkPhoneNormalizer.normalize(raw);
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
