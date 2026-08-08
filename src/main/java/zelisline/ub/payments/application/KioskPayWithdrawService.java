package zelisline.ub.payments.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.KioskPayWithdrawRequest;
import zelisline.ub.payments.api.dto.KioskPayWithdrawalResponse;
import zelisline.ub.payments.domain.KioskPayAccount;
import zelisline.ub.payments.domain.KioskPayWithdrawal;
import zelisline.ub.payments.domain.KioskPayWithdrawalStatuses;
import zelisline.ub.payments.domain.PlatformKioskPaySettings;
import zelisline.ub.payments.domain.spi.SendMoneyRequest;
import zelisline.ub.payments.domain.spi.SendMoneyResult;
import zelisline.ub.payments.domain.spi.WebhookResult;
import zelisline.ub.payments.infrastructure.KopokopoPaymentGateway;
import zelisline.ub.payments.repository.KioskPayWithdrawalRepository;

@Service
@RequiredArgsConstructor
public class KioskPayWithdrawService {

    private static final Logger log = LoggerFactory.getLogger(KioskPayWithdrawService.class);

    /** REQUESTED with no provider id — fail so a new withdraw can start. */
    private static final Duration STALE_REQUESTED = Duration.ofSeconds(30);
    /** PROCESSING without terminal webhook — poll, then expire. */
    private static final Duration STALE_PROCESSING = Duration.ofMinutes(10);

    private final KioskPayWithdrawalRepository withdrawalRepository;
    private final KioskPayWalletService walletService;
    private final PlatformKioskPaySettingsService platformSettings;
    private final KopokopoPaymentGateway kopokopoPaymentGateway;

    @Value("${app.public.api-base-url:http://localhost:5050}")
    private String publicApiBaseUrl;

    @Transactional(readOnly = true)
    public List<KioskPayWithdrawalResponse> list(String businessId, int limit) {
        int capped = Math.min(Math.max(limit, 1), 50);
        return withdrawalRepository.findByBusinessIdOrderByCreatedAtDesc(businessId, PageRequest.of(0, capped))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public KioskPayWithdrawalResponse requestWithdraw(String businessId, KioskPayWithdrawRequest body) {
        if (body == null || body.amount() == null || body.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount must be positive");
        }
        String idem = body.idempotencyKey() != null && !body.idempotencyKey().isBlank()
                ? body.idempotencyKey().trim()
                : UUID.randomUUID().toString();

        var existing = withdrawalRepository.findByBusinessIdAndIdempotencyKey(businessId, idem);
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }

        PlatformKioskPaySettings settings = platformSettings.requireEnabledSettings();
        Map<String, String> kopokopoCreds = platformSettings.kopokopoCredentials()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Platform KopoKopo credentials are not configured for withdrawals"));

        KioskPayAccount account = walletService.getOrCreate(businessId);
        if (!account.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Activate Kiosk Pay before withdrawing");
        }

        String phone = body.phoneNumber() != null && !body.phoneNumber().isBlank()
                ? body.phoneNumber().trim()
                : account.getPayoutPhone();
        if (phone == null || phone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payout phone is required");
        }

        BigDecimal amount = body.amount().setScale(2, java.math.RoundingMode.HALF_UP);
        if (amount.compareTo(settings.getMinWithdrawAmount()) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Minimum withdraw is " + settings.getMinWithdrawAmount());
        }

        // Clear stuck REQUESTED / abandoned PROCESSING before enforcing one-in-flight.
        reconcileInFlight(businessId, account, kopokopoCreds);

        if (withdrawalRepository.existsByBusinessIdAndStatusIn(
                businessId,
                List.of(KioskPayWithdrawalStatuses.REQUESTED, KioskPayWithdrawalStatuses.PROCESSING))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A withdrawal is already in progress — wait for M-Pesa to finish, or try again shortly");
        }

        Instant dayStart = Instant.now().truncatedTo(ChronoUnit.DAYS);
        BigDecimal today = withdrawalRepository.sumSuccessfulSince(businessId, dayStart);
        if (today.add(amount).compareTo(settings.getDailyWithdrawLimit()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Daily withdraw limit exceeded (" + settings.getDailyWithdrawLimit() + ")");
        }

        KioskPayWithdrawal row = new KioskPayWithdrawal();
        row.setBusinessId(businessId);
        row.setAccountId(account.getId());
        row.setAmount(amount);
        row.setCurrency(settings.getCurrency());
        row.setPhoneNumber(phone);
        row.setStatus(KioskPayWithdrawalStatuses.REQUESTED);
        row.setIdempotencyKey(idem);
        try {
            row = withdrawalRepository.save(row);
        } catch (DataIntegrityViolationException e) {
            return toResponse(withdrawalRepository.findByBusinessIdAndIdempotencyKey(businessId, idem)
                    .orElseThrow(() -> e));
        }

        walletService.holdForWithdraw(account, amount, row.getId(), "wd-hold-" + row.getId());

        // KopoKopo source_identifier must be a till number, or null for available balance —
        // never an internal UUID (that yields "Source identifier is invalid").
        String till = firstNonBlank(kopokopoCreds, "tillNumber", "shortcode");

        String callbackBase = publicApiBaseUrl == null ? "" : publicApiBaseUrl.replaceAll("/$", "");
        SendMoneyResult result = kopokopoPaymentGateway.sendMoney(new SendMoneyRequest(
                kopokopoCreds,
                callbackBase,
                phone,
                amount,
                settings.getCurrency(),
                "Kiosk Pay withdraw " + row.getId(),
                till,
                Map.of(
                        "kioskPayWithdrawalId", row.getId(),
                        "businessId", businessId,
                        "purpose", "KIOSK_PAY_WITHDRAW")));

        if (!result.accepted() || result.sendMoneyId() == null || result.sendMoneyId().isBlank()) {
            // Do not throw — ResponseStatusException would roll back FAILED + hold release.
            markFailed(row, account, amount,
                    result.message() != null ? result.message() : "Send Money rejected");
            return toResponse(row);
        }

        row.setKopokopoSendMoneyId(result.sendMoneyId());
        row.setStatus(KioskPayWithdrawalStatuses.PROCESSING);
        row.setProcessingAt(Instant.now());
        withdrawalRepository.save(row);
        log.info("Kiosk Pay withdraw started: id={} sendMoneyId={} business={}",
                row.getId(), result.sendMoneyId(), businessId);
        return toResponse(row);
    }

    /**
     * Settle Send Money webhook for Kiosk Pay withdrawals (platform KopoKopo).
     */
    @Transactional
    public boolean handleSendMoneyWebhook(WebhookResult parsed) {
        if (parsed == null) {
            return false;
        }
        String sendMoneyId = parsed.gatewayTransactionId() != null
                ? parsed.gatewayTransactionId()
                : parsed.gatewayCheckoutId();
        if (sendMoneyId == null || sendMoneyId.isBlank()) {
            return false;
        }
        var opt = withdrawalRepository.findByKopokopoSendMoneyId(sendMoneyId);
        if (opt.isEmpty()) {
            // Also try bare UUID if Location path was stored with a prefix mismatch.
            String trimmed = sendMoneyId.trim();
            int slash = trimmed.lastIndexOf('/');
            if (slash >= 0 && slash < trimmed.length() - 1) {
                opt = withdrawalRepository.findByKopokopoSendMoneyId(trimmed.substring(slash + 1));
            }
        }
        if (opt.isEmpty()) {
            return false;
        }
        KioskPayWithdrawal row = opt.get();
        if (KioskPayWithdrawalStatuses.SUCCESS.equals(row.getStatus())
                || KioskPayWithdrawalStatuses.FAILED.equals(row.getStatus())) {
            return true;
        }

        KioskPayAccount account = walletService.getOrCreate(row.getBusinessId());
        if (parsed.success()) {
            walletService.settleWithdraw(account, row.getAmount(), row.getId(), "wd-settle-" + row.getId());
            row.setStatus(KioskPayWithdrawalStatuses.SUCCESS);
            row.setCompletedAt(Instant.now());
            row.setFailureReason(null);
            withdrawalRepository.save(row);
            return true;
        }
        if (parsed.terminalFailure()) {
            markFailed(row, account, row.getAmount(),
                    parsed.failureMessage() != null ? parsed.failureMessage() : "Send Money failed");
            return true;
        }
        return true;
    }

    /**
     * Poll / expire in-flight withdrawals so a stuck row cannot block the merchant forever.
     */
    private void reconcileInFlight(
            String businessId,
            KioskPayAccount account,
            Map<String, String> kopokopoCreds
    ) {
        Instant now = Instant.now();
        List<KioskPayWithdrawal> inflight = withdrawalRepository
                .findByBusinessIdAndStatusInOrderByCreatedAtAsc(
                        businessId,
                        List.of(KioskPayWithdrawalStatuses.REQUESTED, KioskPayWithdrawalStatuses.PROCESSING));

        for (KioskPayWithdrawal row : inflight) {
            if (KioskPayWithdrawalStatuses.REQUESTED.equals(row.getStatus())) {
                // Never accepted by provider — safe to release immediately (or after a short grace).
                Instant started = row.getRequestedAt() != null ? row.getRequestedAt() : row.getCreatedAt();
                boolean noProviderId = row.getKopokopoSendMoneyId() == null
                        || row.getKopokopoSendMoneyId().isBlank();
                if (noProviderId || (started != null && started.isBefore(now.minus(STALE_REQUESTED)))) {
                    markFailed(row, account, row.getAmount(),
                            "Withdraw abandoned before provider accepted — funds released");
                }
                continue;
            }

            // PROCESSING
            String sendMoneyId = row.getKopokopoSendMoneyId();
            if (sendMoneyId != null && !sendMoneyId.isBlank()) {
                try {
                    WebhookResult status = kopokopoPaymentGateway.querySendMoneyStatus(sendMoneyId, kopokopoCreds);
                    if (status != null && status.success()) {
                        walletService.settleWithdraw(
                                account, row.getAmount(), row.getId(), "wd-settle-" + row.getId());
                        row.setStatus(KioskPayWithdrawalStatuses.SUCCESS);
                        row.setCompletedAt(Instant.now());
                        row.setFailureReason(null);
                        withdrawalRepository.save(row);
                        continue;
                    }
                    if (status != null && status.terminalFailure()) {
                        markFailed(row, account, row.getAmount(),
                                status.failureMessage() != null
                                        ? status.failureMessage()
                                        : "Send Money failed");
                        continue;
                    }
                } catch (Exception e) {
                    log.warn("Kiosk Pay Send Money status poll failed id={}: {}", row.getId(), e.getMessage());
                }
            }

            Instant processingStarted = row.getProcessingAt() != null
                    ? row.getProcessingAt()
                    : row.getRequestedAt();
            if (processingStarted != null && processingStarted.isBefore(now.minus(STALE_PROCESSING))) {
                markFailed(row, account, row.getAmount(),
                        "Withdraw timed out waiting for M-Pesa — funds released; try again");
            }
        }
    }

    private void markFailed(
            KioskPayWithdrawal row,
            KioskPayAccount account,
            BigDecimal amount,
            String reason
    ) {
        if (KioskPayWithdrawalStatuses.SUCCESS.equals(row.getStatus())
                || KioskPayWithdrawalStatuses.FAILED.equals(row.getStatus())) {
            return;
        }
        boolean held = KioskPayWithdrawalStatuses.REQUESTED.equals(row.getStatus())
                || KioskPayWithdrawalStatuses.PROCESSING.equals(row.getStatus());
        if (held) {
            try {
                walletService.releaseWithdrawHold(
                        account, amount, row.getId(), "wd-release-" + row.getId());
            } catch (Exception e) {
                log.error("Kiosk Pay release hold failed for withdrawal={}", row.getId(), e);
            }
        }
        row.setStatus(KioskPayWithdrawalStatuses.FAILED);
        row.setFailureReason(reason != null && reason.length() > 500 ? reason.substring(0, 500) : reason);
        row.setCompletedAt(Instant.now());
        withdrawalRepository.save(row);
        log.info("Kiosk Pay withdraw failed: id={} reason={}", row.getId(), reason);
    }

    private KioskPayWithdrawalResponse toResponse(KioskPayWithdrawal w) {
        return new KioskPayWithdrawalResponse(
                w.getId(),
                w.getAmount(),
                w.getCurrency(),
                w.getPhoneNumber(),
                w.getStatus(),
                w.getFailureReason(),
                w.getRequestedAt(),
                w.getCompletedAt());
    }

    private static String firstNonBlank(Map<String, String> creds, String... keys) {
        if (creds == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            String v = creds.get(key);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
