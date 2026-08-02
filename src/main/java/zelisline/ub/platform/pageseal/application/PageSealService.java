package zelisline.ub.platform.pageseal.application;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.KenyanPhoneForms;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.identity.application.TokenHasher;
import zelisline.ub.marketplace.application.SupplierPortalProfileService;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.SupplierUser;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.marketplace.repository.SupplierUserRepository;
import zelisline.ub.messaging.application.CustomerMessageDispatcher;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.platform.pageseal.api.dto.PageSealOkResponse;
import zelisline.ub.platform.pageseal.api.dto.PageSealSendCodeResponse;
import zelisline.ub.platform.pageseal.api.dto.PageSealStatusResponse;
import zelisline.ub.platform.pageseal.api.dto.PageSealUnlockResponse;
import zelisline.ub.platform.pageseal.domain.PageSealChallenge;
import zelisline.ub.platform.pageseal.domain.PageSealScopes;
import zelisline.ub.platform.pageseal.domain.PageSealUnlock;
import zelisline.ub.platform.pageseal.repository.PageSealChallengeRepository;
import zelisline.ub.platform.pageseal.repository.PageSealUnlockRepository;

/**
 * Phone-OTP seal + 4-digit PIN for public supplier passports and customer tabs.
 */
@Service
@RequiredArgsConstructor
public class PageSealService {

    private static final Logger log = LoggerFactory.getLogger(PageSealService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final Duration SETUP_TTL = Duration.ofMinutes(15);
    private static final Duration UNLOCK_TTL = Duration.ofHours(12);
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(45);
    private static final Duration LOCK_TTL = Duration.ofMinutes(15);
    private static final int MAX_ATTEMPTS = 5;
    private static final int CODE_DIGITS = 6;

    private final PageSealChallengeRepository challengeRepository;
    private final PageSealUnlockRepository unlockRepository;
    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final SupplierUserRepository supplierUserRepository;
    private final CustomerRepository customerRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final CustomerMessageDispatcher customerMessageDispatcher;

    @Value("${app.supplier-portal.claim.return-otp-when-stubbed:false}")
    private boolean returnOtpWhenStubbed;

    @Transactional(readOnly = true)
    public PageSealStatusResponse supplierStatusById(String marketplaceSupplierId, String unlockToken) {
        MarketplaceSupplier supplier = marketplaceSupplierRepository.findById(marketplaceSupplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        boolean unlockValid = isUnlockValid(PageSealScopes.SUPPLIER_SLUG, supplier.getId(), unlockToken);
        return new PageSealStatusResponse(
                supplier.isPageSealed(),
                PageSealScopes.SUPPLIER_SLUG,
                supplier.getUsername(),
                supplier.getName(),
                maskPhone(resolveSupplierPhone(supplier)),
                unlockValid);
    }

    @Transactional(readOnly = true)
    public PageSealStatusResponse supplierStatus(String usernameRaw, String unlockToken) {
        MarketplaceSupplier supplier = requireSupplierByUsername(usernameRaw);
        boolean unlockValid = isUnlockValid(PageSealScopes.SUPPLIER_SLUG, supplier.getId(), unlockToken);
        return new PageSealStatusResponse(
                supplier.isPageSealed(),
                PageSealScopes.SUPPLIER_SLUG,
                supplier.getUsername(),
                supplier.getName(),
                maskPhone(resolveSupplierPhone(supplier)),
                unlockValid);
    }

    @Transactional(readOnly = true)
    public PageSealStatusResponse customerTabStatus(
            String businessId,
            String phoneRaw,
            String unlockToken
    ) {
        ResolvedTab tab = resolveTab(businessId, phoneRaw);
        boolean unlockValid = isUnlockValid(
                PageSealScopes.CUSTOMER_TAB, tab.account().getId(), unlockToken);
        return new PageSealStatusResponse(
                tab.account().isPageSealed(),
                PageSealScopes.CUSTOMER_TAB,
                tab.phoneKey(),
                tab.customer().getName(),
                maskPhone(tab.phoneNormalized()),
                unlockValid);
    }

    public boolean isSupplierUnlocked(MarketplaceSupplier supplier, String unlockToken) {
        if (supplier == null || !supplier.isPageSealed()) {
            return true;
        }
        return isUnlockValid(PageSealScopes.SUPPLIER_SLUG, supplier.getId(), unlockToken);
    }

    public boolean isCustomerTabUnlocked(CreditAccount account, String unlockToken) {
        if (account == null || !account.isPageSealed()) {
            return true;
        }
        return isUnlockValid(PageSealScopes.CUSTOMER_TAB, account.getId(), unlockToken);
    }

    @Transactional
    public PageSealSendCodeResponse sendSupplierSealCode(String marketplaceSupplierId) {
        MarketplaceSupplier supplier = marketplaceSupplierRepository.findById(marketplaceSupplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        String phone = resolveSupplierPhone(supplier);
        if (phone == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Add a phone number on your supplier profile before sealing this page");
        }
        return sendCode(PageSealScopes.SUPPLIER_SLUG, supplier.getId(), phone);
    }

    @Transactional
    public PageSealOkResponse verifyAndSealSupplier(
            String marketplaceSupplierId,
            String code,
            String pin,
            String confirmPin
    ) {
        MarketplaceSupplier supplier = marketplaceSupplierRepository.findById(marketplaceSupplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        verifyCodeAndConsume(PageSealScopes.SUPPLIER_SLUG, supplier.getId(), code);
        String normalizedPin = normalizePin(pin, confirmPin);
        Instant now = Instant.now();
        supplier.setPagePinHash(encodePin(PageSealScopes.SUPPLIER_SLUG, supplier.getId(), normalizedPin));
        supplier.setPageSealed(true);
        supplier.setPageSealVerifiedAt(now);
        supplier.setPageSealUpdatedAt(now);
        marketplaceSupplierRepository.save(supplier);
        return new PageSealOkResponse(true, true);
    }

    @Transactional
    public PageSealOkResponse unsealSupplier(String marketplaceSupplierId, String pin) {
        MarketplaceSupplier supplier = marketplaceSupplierRepository.findById(marketplaceSupplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        if (!supplier.isPageSealed()) {
            return new PageSealOkResponse(true, false);
        }
        assertPin(PageSealScopes.SUPPLIER_SLUG, supplier.getId(), supplier.getPagePinHash(), pin);
        supplier.setPageSealed(false);
        supplier.setPagePinHash(null);
        supplier.setPageSealUpdatedAt(Instant.now());
        marketplaceSupplierRepository.save(supplier);
        return new PageSealOkResponse(true, false);
    }

    @Transactional
    public PageSealUnlockResponse unlockSupplier(String usernameRaw, String pin) {
        MarketplaceSupplier supplier = requireSupplierByUsername(usernameRaw);
        if (!supplier.isPageSealed()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This passport is not sealed");
        }
        assertPin(PageSealScopes.SUPPLIER_SLUG, supplier.getId(), supplier.getPagePinHash(), pin);
        return mintUnlock(PageSealScopes.SUPPLIER_SLUG, supplier.getId());
    }

    @Transactional
    public PageSealSendCodeResponse sendCustomerTabSealCode(String businessId, String phoneRaw) {
        ResolvedTab tab = resolveTab(businessId, phoneRaw);
        return sendCode(PageSealScopes.CUSTOMER_TAB, tab.account().getId(), tab.phoneNormalized());
    }

    @Transactional
    public PageSealOkResponse verifyAndSealCustomerTab(
            String businessId,
            String phoneRaw,
            String code,
            String pin,
            String confirmPin
    ) {
        ResolvedTab tab = resolveTab(businessId, phoneRaw);
        verifyCodeAndConsume(PageSealScopes.CUSTOMER_TAB, tab.account().getId(), code);
        String normalizedPin = normalizePin(pin, confirmPin);
        Instant now = Instant.now();
        CreditAccount account = tab.account();
        account.setPagePinHash(encodePin(PageSealScopes.CUSTOMER_TAB, account.getId(), normalizedPin));
        account.setPageSealed(true);
        account.setPageSealVerifiedAt(now);
        account.setPageSealUpdatedAt(now);
        creditAccountRepository.save(account);
        return new PageSealOkResponse(true, true);
    }

    @Transactional
    public PageSealOkResponse unsealCustomerTab(String businessId, String phoneRaw, String pin) {
        ResolvedTab tab = resolveTab(businessId, phoneRaw);
        CreditAccount account = tab.account();
        if (!account.isPageSealed()) {
            return new PageSealOkResponse(true, false);
        }
        assertPin(PageSealScopes.CUSTOMER_TAB, account.getId(), account.getPagePinHash(), pin);
        account.setPageSealed(false);
        account.setPagePinHash(null);
        account.setPageSealUpdatedAt(Instant.now());
        creditAccountRepository.save(account);
        return new PageSealOkResponse(true, false);
    }

    @Transactional
    public PageSealUnlockResponse unlockCustomerTab(String businessId, String phoneRaw, String pin) {
        ResolvedTab tab = resolveTab(businessId, phoneRaw);
        CreditAccount account = tab.account();
        if (!account.isPageSealed()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This tab is not sealed");
        }
        assertPin(PageSealScopes.CUSTOMER_TAB, account.getId(), account.getPagePinHash(), pin);
        return mintUnlock(PageSealScopes.CUSTOMER_TAB, account.getId());
    }

    private PageSealSendCodeResponse sendCode(String scope, String subjectId, String phone) {
        Instant now = Instant.now();
        challengeRepository.findFirstByScopeAndSubjectIdAndConsumedAtIsNullOrderByCreatedAtDesc(scope, subjectId)
                .ifPresent(open -> {
                    if (open.getLockedUntil() != null && open.getLockedUntil().isAfter(now)) {
                        throw new ResponseStatusException(
                                HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Try again later");
                    }
                    if (open.getLastSentAt() != null
                            && open.getLastSentAt().plus(RESEND_COOLDOWN).isAfter(now)
                            && open.getVerifiedAt() == null) {
                        throw new ResponseStatusException(
                                HttpStatus.TOO_MANY_REQUESTS, "Wait before requesting another code");
                    }
                });
        for (PageSealChallenge open : challengeRepository.findByScopeAndSubjectIdAndConsumedAtIsNull(scope, subjectId)) {
            open.setConsumedAt(now);
            challengeRepository.save(open);
        }

        String code = generateOtp(CODE_DIGITS);
        PageSealChallenge challenge = new PageSealChallenge();
        challenge.setScope(scope);
        challenge.setSubjectId(subjectId);
        challenge.setPhone(phone);
        challenge.setCodeHash(TokenHasher.sha256Hex(code));
        challenge.setExpiresAt(now.plus(CODE_TTL));
        challenge.setAttempts(0);
        challenge.setMaxAttempts(MAX_ATTEMPTS);
        challenge.setLastSentAt(now);
        challengeRepository.save(challenge);

        TenantMessagingConfig messaging = messagingSettingsService.resolvePlatformForContactReply();
        boolean messagingReady = messaging.enabled()
                && (messaging.smsConfigured() || messaging.metaWhatsAppConfigured());
        String channel;
        String outcome;
        String message = "Your Kiosk seal code is " + code + ". Valid for "
                + CODE_TTL.toMinutes() + " minutes. Do not share it.";
        if (!messagingReady) {
            log.info("Page seal OTP stub: scope={} subject={} code={}", scope, subjectId, code);
            channel = "sms_stub";
            outcome = "stub";
        } else {
            var delivery = customerMessageDispatcher.deliver(messaging, phone, message);
            channel = delivery.channel();
            outcome = delivery.outcome();
            if (!"sent".equals(outcome) && !"stub".equals(outcome)) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not send verification code");
            }
        }
        String devCode = returnOtpWhenStubbed && "stub".equals(outcome) ? code : null;
        return new PageSealSendCodeResponse(maskPhone(phone), challenge.getExpiresAt(), channel, devCode);
    }

    private void verifyCodeAndConsume(String scope, String subjectId, String rawCode) {
        String code = rawCode == null ? "" : rawCode.trim();
        if (!code.matches("\\d{" + CODE_DIGITS + "}")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Enter the " + CODE_DIGITS + "-digit code from SMS");
        }
        PageSealChallenge challenge = challengeRepository
                .findFirstByScopeAndSubjectIdAndConsumedAtIsNullOrderByCreatedAtDesc(scope, subjectId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "No active code — request a new SMS first"));
        Instant now = Instant.now();
        if (challenge.getLockedUntil() != null && challenge.getLockedUntil().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Try again later");
        }
        if (challenge.getExpiresAt().isBefore(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Code expired. Request another.");
        }
        if (challenge.getAttempts() >= challenge.getMaxAttempts()) {
            challenge.setLockedUntil(now.plus(LOCK_TTL));
            challengeRepository.save(challenge);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Try again later");
        }
        if (!constantTimeEquals(challenge.getCodeHash(), TokenHasher.sha256Hex(code))) {
            challenge.setAttempts(challenge.getAttempts() + 1);
            if (challenge.getAttempts() >= challenge.getMaxAttempts()) {
                challenge.setLockedUntil(now.plus(LOCK_TTL));
            }
            challengeRepository.save(challenge);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Incorrect code");
        }
        challenge.setVerifiedAt(now);
        challenge.setConsumedAt(now);
        challengeRepository.save(challenge);
    }

    private PageSealUnlockResponse mintUnlock(String scope, String subjectId) {
        Instant now = Instant.now();
        Instant expires = now.plus(UNLOCK_TTL);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        PageSealUnlock unlock = new PageSealUnlock();
        unlock.setScope(scope);
        unlock.setSubjectId(subjectId);
        unlock.setTokenHash(TokenHasher.sha256Hex(token));
        unlock.setExpiresAt(expires);
        unlockRepository.save(unlock);
        return new PageSealUnlockResponse(token, expires);
    }

    private boolean isUnlockValid(String scope, String subjectId, String unlockToken) {
        if (unlockToken == null || unlockToken.isBlank()) {
            return false;
        }
        return unlockRepository
                .findByTokenHashAndExpiresAtAfter(TokenHasher.sha256Hex(unlockToken.trim()), Instant.now())
                .filter(u -> scope.equals(u.getScope()) && subjectId.equals(u.getSubjectId()))
                .isPresent();
    }

    private void assertPin(String scope, String subjectId, String pinHash, String rawPin) {
        String pin = rawPin == null ? "" : rawPin.trim();
        if (!pin.matches("\\d{4}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter your 4-digit seal PIN");
        }
        if (pinHash == null || pinHash.isBlank()
                || !passwordEncoder.matches(scope + ":" + subjectId + ":" + pin, pinHash)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect PIN");
        }
    }

    private String encodePin(String scope, String subjectId, String pin) {
        return passwordEncoder.encode(scope + ":" + subjectId + ":" + pin);
    }

    private static String normalizePin(String pin, String confirmPin) {
        String p = pin == null ? "" : pin.trim();
        String c = confirmPin == null ? "" : confirmPin.trim();
        if (!p.matches("\\d{4}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a 4-digit PIN");
        }
        if (!p.equals(c)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PIN confirmation does not match");
        }
        return p;
    }

    private MarketplaceSupplier requireSupplierByUsername(String usernameRaw) {
        String username = SupplierPortalProfileService.normalizeUsername(usernameRaw);
        if (username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found");
        }
        return marketplaceSupplierRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
    }

    private String resolveSupplierPhone(MarketplaceSupplier supplier) {
        if (supplier.getContactPhone() != null && !supplier.getContactPhone().isBlank()) {
            String normalized = StkPhoneNormalizer.normalize(supplier.getContactPhone());
            if (normalized != null) {
                return normalized;
            }
        }
        List<SupplierUser> users =
                supplierUserRepository.findByMarketplaceSupplierIdAndActiveTrue(supplier.getId());
        for (SupplierUser user : users) {
            if (user.getPhone() != null && !user.getPhone().isBlank()) {
                String normalized = StkPhoneNormalizer.normalize(user.getPhone());
                if (normalized != null) {
                    return normalized;
                }
            }
        }
        return null;
    }

    private ResolvedTab resolveTab(String businessId, String phoneRaw) {
        if (!KenyanPhoneForms.looksLikeKenyanMobile(phoneRaw)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer tab not found");
        }
        Customer customer = null;
        for (String candidate : KenyanPhoneForms.lookupCandidates(phoneRaw)) {
            var page = customerRepository.findByBusinessIdAndPhoneNormalized(
                    businessId, candidate, org.springframework.data.domain.PageRequest.of(0, 1));
            if (!page.isEmpty()) {
                customer = page.getContent().getFirst();
                break;
            }
        }
        if (customer == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer tab not found");
        }
        CreditAccount account = creditAccountRepository
                .findByCustomerIdAndBusinessId(customer.getId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer tab not found"));
        String local07 = KenyanPhoneForms.toLocal07(phoneRaw);
        String normalized = StkPhoneNormalizer.normalize(local07 != null ? local07 : phoneRaw);
        if (normalized == null) {
            normalized = local07 != null ? local07 : phoneRaw.trim();
        }
        return new ResolvedTab(customer, account, normalized, local07 != null ? local07 : phoneRaw.trim());
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 4) {
            return "****";
        }
        return "••• ••• " + digits.substring(digits.length() - 4);
    }

    private static String generateOtp(int digits) {
        int bound = (int) Math.pow(10, digits);
        int n = RANDOM.nextInt(bound / 10, bound);
        return String.format(Locale.ROOT, "%0" + digits + "d", n);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int r = 0;
        for (int i = 0; i < a.length(); i++) {
            r |= a.charAt(i) ^ b.charAt(i);
        }
        return r == 0;
    }

    private record ResolvedTab(
            Customer customer,
            CreditAccount account,
            String phoneNormalized,
            String phoneKey
    ) {
    }
}
