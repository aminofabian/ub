package zelisline.ub.payments.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.MpesaStkStatuses;
import zelisline.ub.credits.application.WalletLedgerService;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.MpesaStkIntent;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.MpesaStkIntentRepository;
import zelisline.ub.notifications.application.NotificationOutboxService;
import zelisline.ub.payments.domain.GatewayStkPush;
import zelisline.ub.payments.domain.GatewayStkPushStatuses;
import zelisline.ub.payments.domain.GatewayStatus;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.PaymentWebhookEvent;
import zelisline.ub.payments.domain.StkPushContextType;
import zelisline.ub.payments.domain.spi.WebhookResult;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.payments.infrastructure.KopokopoPaymentGateway;
import zelisline.ub.payments.repository.GatewayStkPushRepository;
import zelisline.ub.payments.repository.PaymentGatewayConfigRepository;
import zelisline.ub.payments.repository.PaymentWebhookEventRepository;
import zelisline.ub.platform.realtime.RealtimeBridge;
import zelisline.ub.storefront.WebOrderStatuses;
import zelisline.ub.storefront.application.WebOrderFulfillmentService;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.repository.WebOrderRepository;

@Service
@RequiredArgsConstructor
public class GatewayStkPushService {

    private static final Logger log = LoggerFactory.getLogger(GatewayStkPushService.class);

    private static final int RECONCILE_LOOKBACK_HOURS = 48;

    /** After this age, a still-pending local STK row is marked failed so cashier can retry. */
    @Value("${app.payments.stk.stale-pending-seconds:30}")
    private int stalePendingSeconds;

    private final GatewayStkPushRepository pushRepository;
    private final PaymentWebhookEventRepository webhookEventRepository;
    private final PaymentGatewayConfigRepository configRepository;
    private final WebOrderRepository webOrderRepository;
    private final MpesaStkIntentRepository mpesaStkIntentRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final WalletLedgerService walletLedgerService;
    private final CredentialEncryptionService encryptionService;
    private final KopokopoPaymentGateway kopokopoGateway;
    private final ObjectMapper objectMapper;
    private final NotificationOutboxService notificationOutboxService;
    private final WebOrderFulfillmentService webOrderFulfillmentService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Transactional
    public GatewayStkPush registerPush(
            String businessId,
            GatewayType gatewayType,
            String configId,
            String gatewayCheckoutId,
            String merchantReference,
            StkPushContextType contextType,
            String contextId,
            BigDecimal amount,
            String phoneNumber
    ) {
        if (gatewayCheckoutId == null || gatewayCheckoutId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing gateway checkout id");
        }
        Optional<GatewayStkPush> existing = pushRepository.findByGatewayTypeAndGatewayCheckoutId(
                gatewayType, gatewayCheckoutId.trim());
        if (existing.isPresent()) {
            return existing.get();
        }

        GatewayStkPush row = new GatewayStkPush();
        row.setBusinessId(businessId);
        row.setGatewayType(gatewayType);
        row.setConfigId(configId);
        row.setGatewayCheckoutId(gatewayCheckoutId.trim());
        row.setMerchantReference(merchantReference != null ? merchantReference.trim() : gatewayCheckoutId.trim());
        row.setContextType(contextType);
        row.setContextId(contextId);
        row.setAmount(amount);
        String normalizedPhone = StkPhoneNormalizer.normalize(phoneNumber);
        row.setPhoneNumber(normalizedPhone != null ? normalizedPhone : (phoneNumber != null ? phoneNumber.trim() : ""));
        row.setStatus(GatewayStkPushStatuses.PENDING);
        try {
            return pushRepository.save(row);
        } catch (DataIntegrityViolationException e) {
            return pushRepository.findByGatewayTypeAndGatewayCheckoutId(gatewayType, gatewayCheckoutId.trim())
                    .orElseThrow(() -> e);
        }
    }

    @Transactional
    public boolean processKopokopoWebhook(
            String businessId,
            String configId,
            WebhookResult parsed
    ) {
        if (parsed == null) {
            return false;
        }
        String eventId = parsed.webhookEventId() != null && !parsed.webhookEventId().isBlank()
                ? parsed.webhookEventId()
                : parsed.gatewayTransactionId();
        if (eventId != null && !eventId.isBlank()) {
            if (webhookEventRepository.existsByGatewayTypeAndGatewayEventId(GatewayType.KOPOKOPO, eventId)) {
                log.info("KopoKopo webhook duplicate ignored: eventId={}", eventId);
                return true;
            }
            PaymentWebhookEvent audit = new PaymentWebhookEvent();
            audit.setBusinessId(businessId);
            audit.setGatewayType(GatewayType.KOPOKOPO);
            audit.setGatewayEventId(eventId);
            audit.setTopic(parsed.topic());
            audit.setRawPayload(parsed.rawPayload());
            webhookEventRepository.save(audit);
        }

        Optional<GatewayStkPush> push = resolvePush(businessId, parsed);
        if (push.isEmpty()) {
            log.warn("KopoKopo webhook: no matching STK push business={} checkout={} ref={}",
                    businessId, parsed.gatewayCheckoutId(), parsed.reference());
            return true;
        }

        if (parsed.success()) {
            confirmPush(push.get(), parsed.gatewayTransactionId(), parsed.amount());
            return true;
        }
        if (parsed.terminalFailure()) {
            markFailed(push.get(), "Payment declined by M-Pesa");
            return true;
        }
        return true;
    }

    @Transactional
    public Optional<GatewayStkPush> pollAndUpdate(GatewayStkPush push) {
        if (!GatewayStkPushStatuses.PENDING.equals(push.getStatus())) {
            return Optional.of(push);
        }
        if (push.getGatewayType() != GatewayType.KOPOKOPO) {
            return Optional.of(push);
        }
        PaymentGatewayConfig cfg = resolveConfig(push);
        if (cfg == null) {
            return Optional.of(push);
        }

        Map<String, String> creds = decryptCredentials(cfg);
        if (creds == null) {
            return Optional.of(push);
        }

        var status = kopokopoGateway.queryStkStatus(push.getGatewayCheckoutId(), creds);
        push.setLastPolledAt(Instant.now());
        push.setPollCount(push.getPollCount() + 1);
        pushRepository.save(push);

        if (status.completed()) {
            String receipt = status.mpesaReceipt();
            if (receipt == null || receipt.isBlank()) {
                log.warn("STK poll completed without M-Pesa receipt — leaving pending pushId={}",
                        push.getId());
                return Optional.of(push);
            }
            confirmPush(push, receipt.trim(), push.getAmount());
            return Optional.of(pushRepository.findById(push.getId()).orElse(push));
        }
        if (status.failed()) {
            markFailed(push, status.resultDescription() != null ? status.resultDescription() : "STK payment failed");
            return Optional.of(pushRepository.findById(push.getId()).orElse(push));
        }
        return Optional.of(push);
    }

    /**
     * Polls KopoKopo for open STK pushes on this phone so a new prompt can be sent after
     * the previous one failed or timed out. KopoKopo rejects duplicate prompts while one is pending.
     */
    @Transactional
    public ReconcileResult reconcilePendingForPhone(String businessId, String rawPhone) {
        String phone = StkPhoneNormalizer.normalize(rawPhone);
        if (phone == null || businessId == null || businessId.isBlank()) {
            return new ReconcileResult(0, false);
        }
        Instant since = Instant.now().minus(RECONCILE_LOOKBACK_HOURS, ChronoUnit.HOURS);
        List<GatewayStkPush> pending = pushRepository
                .findByBusinessIdAndPhoneNumberAndStatusAndCreatedAtAfterOrderByCreatedAtAsc(
                        businessId, phone, GatewayStkPushStatuses.PENDING, since);
        if (pending.isEmpty()) {
            return new ReconcileResult(0, false);
        }
        int terminalUpdates = 0;
        for (GatewayStkPush push : pending) {
            Optional<GatewayStkPush> updated = pollAndUpdate(push);
            GatewayStkPush row = updated.orElse(push);
            if (!GatewayStkPushStatuses.PENDING.equals(row.getStatus())) {
                terminalUpdates++;
            }
        }
        expireStalePendingForPhone(businessId, phone);
        boolean stillOpen = pushRepository
                .findByBusinessIdAndPhoneNumberAndStatusAndCreatedAtAfterOrderByCreatedAtAsc(
                        businessId, phone, GatewayStkPushStatuses.PENDING, since)
                .stream()
                .anyMatch(p -> GatewayStkPushStatuses.PENDING.equals(p.getStatus()));
        return new ReconcileResult(terminalUpdates, stillOpen);
    }

    @Transactional
    public void expireStalePendingForPhone(String businessId, String rawPhone) {
        String phone = StkPhoneNormalizer.normalize(rawPhone);
        if (phone == null) {
            return;
        }
        Instant since = Instant.now().minus(RECONCILE_LOOKBACK_HOURS, ChronoUnit.HOURS);
        Instant staleBefore = Instant.now().minus(Math.max(stalePendingSeconds, 5), ChronoUnit.SECONDS);
        List<GatewayStkPush> pending = pushRepository
                .findByBusinessIdAndPhoneNumberAndStatusAndCreatedAtAfterOrderByCreatedAtAsc(
                        businessId, phone, GatewayStkPushStatuses.PENDING, since);
        for (GatewayStkPush push : pending) {
            if (push.getCreatedAt() != null && push.getCreatedAt().isBefore(staleBefore)) {
                markFailed(push, "M-Pesa prompt timed out — you can send a new prompt");
                log.info("STK push timed out locally: pushId={} phone={}", push.getId(), phone);
            }
        }
    }

    /**
     * Polls KopoKopo for recent STK pushes on this phone (including rows we already
     * marked failed locally) so we know whether Safaricom still holds a prompt lock.
     */
    @Transactional
    public PhoneClearResult settleRecentPushesForPhone(String businessId, String rawPhone) {
        String phone = StkPhoneNormalizer.normalize(rawPhone);
        if (phone == null || businessId == null || businessId.isBlank()) {
            return new PhoneClearResult(false, 0);
        }
        Instant since = Instant.now().minus(RECONCILE_LOOKBACK_HOURS, ChronoUnit.HOURS);
        Instant forcePollFailedSince = Instant.now().minus(20, ChronoUnit.MINUTES);
        List<GatewayStkPush> recent = pushRepository
                .findByBusinessIdAndPhoneNumberAndCreatedAtAfterOrderByCreatedAtDesc(
                        businessId, phone, since);
        int terminalUpdates = 0;
        boolean gatewayPending = false;
        for (GatewayStkPush push : recent) {
            if (push.getGatewayCheckoutId() == null || push.getGatewayCheckoutId().isBlank()) {
                continue;
            }
            if (GatewayStkPushStatuses.SUCCESS.equals(push.getStatus())) {
                continue;
            }
            boolean localPending = GatewayStkPushStatuses.PENDING.equals(push.getStatus());
            boolean recentFailed = GatewayStkPushStatuses.FAILED.equals(push.getStatus())
                    && push.getCreatedAt() != null
                    && push.getCreatedAt().isAfter(forcePollFailedSince);
            if (!localPending && !recentFailed) {
                continue;
            }
            if (push.getGatewayType() != GatewayType.KOPOKOPO) {
                if (localPending) {
                    gatewayPending = true;
                }
                continue;
            }

            PaymentGatewayConfig cfg = resolveConfig(push);
            Map<String, String> creds = cfg != null ? decryptCredentials(cfg) : null;
            if (creds == null) {
                if (localPending) {
                    gatewayPending = true;
                }
                continue;
            }

            var status = kopokopoGateway.queryStkStatus(push.getGatewayCheckoutId(), creds);
            if (localPending) {
                push.setLastPolledAt(Instant.now());
                push.setPollCount(push.getPollCount() + 1);
                pushRepository.save(push);
            }

            if (status.completed()) {
                if (localPending) {
                    String receipt = status.mpesaReceipt();
                    if (receipt == null || receipt.isBlank()) {
                        log.warn("STK settle completed without M-Pesa receipt — leaving pending pushId={}",
                                push.getId());
                        gatewayPending = true;
                    } else {
                        confirmPush(push, receipt.trim(), push.getAmount());
                        terminalUpdates++;
                    }
                }
            } else if (status.failed()) {
                if (localPending) {
                    String reason = status.resultDescription() != null
                            ? status.resultDescription()
                            : "STK payment failed";
                    markFailed(push, reason);
                    terminalUpdates++;
                }
            } else {
                gatewayPending = true;
            }
        }
        if (!gatewayPending) {
            expireStalePendingForPhone(businessId, phone);
            boolean stillLocalPending = pushRepository
                    .findByBusinessIdAndPhoneNumberAndStatusAndCreatedAtAfterOrderByCreatedAtAsc(
                            businessId, phone, GatewayStkPushStatuses.PENDING, since)
                    .stream()
                    .anyMatch(p -> GatewayStkPushStatuses.PENDING.equals(p.getStatus()));
            gatewayPending = stillLocalPending;
        }
        return new PhoneClearResult(gatewayPending, terminalUpdates);
    }

    /**
     * Marks every still-pending STK for this phone as failed so a cashier/customer
     * can send a new prompt after a decline, cancel, or timeout — without waiting
     * for the stale-pending window.
     */
    @Transactional
    public int cancelPendingForPhone(String businessId, String rawPhone, String reason) {
        String phone = StkPhoneNormalizer.normalize(rawPhone);
        if (phone == null || businessId == null || businessId.isBlank()) {
            return 0;
        }
        Instant since = Instant.now().minus(RECONCILE_LOOKBACK_HOURS, ChronoUnit.HOURS);
        List<GatewayStkPush> pending = pushRepository
                .findByBusinessIdAndPhoneNumberAndStatusAndCreatedAtAfterOrderByCreatedAtAsc(
                        businessId, phone, GatewayStkPushStatuses.PENDING, since);
        String failReason = reason != null && !reason.isBlank()
                ? reason.trim()
                : "Replaced by a new M-Pesa prompt";
        int cancelled = 0;
        for (GatewayStkPush push : pending) {
            if (GatewayStkPushStatuses.PENDING.equals(push.getStatus())) {
                markFailed(push, failReason);
                cancelled++;
            }
        }
        if (cancelled > 0) {
            log.info("Cancelled {} pending STK push(es) for phone={} business={}", cancelled, phone, businessId);
        }
        return cancelled;
    }

    public static boolean isKopokopoPendingPhoneError(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String m = message.toLowerCase(Locale.ROOT);
        boolean mentionsPhone = m.contains("phone");
        boolean mentionsPending = m.contains("pending request")
                || m.contains("pending payment")
                || m.contains("another pending")
                || m.contains("already has an active")
                || (m.contains("pending") && m.contains("request"));
        return mentionsPhone && mentionsPending;
    }

    public static String pendingPhoneUserMessage() {
        return "Previous M-Pesa prompt is still open on this phone. "
                + "Ask the customer to Cancel it (or wait about a minute), then try again.";
    }

    public record ReconcileResult(int terminalUpdates, boolean hasOpenPending) {
    }

    public record PhoneClearResult(boolean hasGatewayPending, int terminalUpdates) {
    }

    @Transactional
    public void markTimedOutIfPollsExhausted(GatewayStkPush push, int maxPolls) {
        if (push == null || maxPolls <= 0) {
            return;
        }
        if (push.getPollCount() >= maxPolls
                && GatewayStkPushStatuses.PENDING.equals(push.getStatus())) {
            markFailed(push, "M-Pesa prompt timed out — you can send a new prompt");
        }
    }

    @Transactional(readOnly = true)
    public Optional<GatewayStkPush> findByCheckoutId(GatewayType type, String checkoutId) {
        if (checkoutId == null || checkoutId.isBlank()) {
            return Optional.empty();
        }
        return pushRepository.findByGatewayTypeAndGatewayCheckoutId(type, checkoutId.trim());
    }

    @Transactional(readOnly = true)
    public Optional<GatewayStkPush> findLatestForWebOrder(String orderId) {
        return pushRepository.findFirstByContextTypeAndContextIdOrderByCreatedAtDesc(
                StkPushContextType.WEB_ORDER, orderId);
    }

    private Optional<GatewayStkPush> resolvePush(String businessId, WebhookResult parsed) {
        if (parsed.gatewayCheckoutId() != null && !parsed.gatewayCheckoutId().isBlank()) {
            Optional<GatewayStkPush> byCheckout = pushRepository.findByGatewayTypeAndGatewayCheckoutId(
                    GatewayType.KOPOKOPO, parsed.gatewayCheckoutId().trim());
            if (byCheckout.isPresent() && businessId.equals(byCheckout.get().getBusinessId())) {
                return byCheckout;
            }
        }
        if (parsed.reference() != null && !parsed.reference().isBlank()) {
            return pushRepository.findFirstByBusinessIdAndMerchantReferenceAndStatus(
                    businessId, parsed.reference().trim(), GatewayStkPushStatuses.PENDING);
        }
        return Optional.empty();
    }

    private void confirmPush(GatewayStkPush push, String gatewayTxnId, BigDecimal webhookAmount) {
        if (!GatewayStkPushStatuses.PENDING.equals(push.getStatus())) {
            return;
        }
        if (webhookAmount != null
                && webhookAmount.subtract(push.getAmount()).abs().compareTo(new BigDecimal("1.00")) > 0) {
            log.warn("STK amount mismatch push={} expected={} got={}",
                    push.getId(), push.getAmount(), webhookAmount);
        }

        push.setStatus(GatewayStkPushStatuses.SUCCESS);
        push.setGatewayTransactionId(gatewayTxnId);
        push.setConfirmedAt(Instant.now());
        push.setFailureReason(null);
        pushRepository.save(push);

        switch (push.getContextType()) {
            case WEB_ORDER -> confirmWebOrder(push);
            case WALLET_INTENT -> confirmWalletIntent(push);
            case POS_PAYMENT -> publishPosConfirmation(push);
            default -> log.warn("Unknown STK context type: {}", push.getContextType());
        }
    }

    private void markFailed(GatewayStkPush push, String reason) {
        if (!GatewayStkPushStatuses.PENDING.equals(push.getStatus())) {
            return;
        }
        push.setStatus(GatewayStkPushStatuses.FAILED);
        push.setFailureReason(reason);
        pushRepository.save(push);

        if (push.getContextType() == StkPushContextType.WEB_ORDER && push.getContextId() != null) {
            webOrderRepository.findById(push.getContextId()).ifPresent(order -> {
                if (WebOrderStatuses.PENDING_PAYMENT.equals(order.getStatus())) {
                    order.setStatus(WebOrderStatuses.PAYMENT_FAILED);
                    webOrderRepository.save(order);
                }
            });
        }
        if (push.getContextType() == StkPushContextType.WALLET_INTENT) {
            mpesaStkIntentRepository.findByBusinessIdAndIdempotencyKey(
                    push.getBusinessId(), push.getMerchantReference()).ifPresent(intent -> {
                if (MpesaStkStatuses.PENDING.equals(intent.getStatus())) {
                    intent.setStatus(MpesaStkStatuses.FAILED);
                    mpesaStkIntentRepository.save(intent);
                }
            });
        }
        publishStkRealtime(push, false, reason);
    }

    private void confirmWebOrder(GatewayStkPush push) {
        if (push.getContextId() == null) {
            return;
        }
        WebOrder order = webOrderRepository.findById(push.getContextId()).orElse(null);
        if (order == null || !push.getBusinessId().equals(order.getBusinessId())) {
            return;
        }
        if (!WebOrderStatuses.PENDING_PAYMENT.equals(order.getStatus())
                && !WebOrderStatuses.PAYMENT_FAILED.equals(order.getStatus())) {
            return;
        }
        order.setStatus(WebOrderStatuses.PAID);
        order.setPaymentCheckoutId(push.getGatewayCheckoutId());
        order.setPaidAt(Instant.now());
        webOrderRepository.save(order);

        try {
            webOrderFulfillmentService.onOrderPaid(order);
            notificationOutboxService.enqueueWebOrderPaid(order);
        } catch (Exception e) {
            log.warn("Failed notifications for paid web order {}", order.getId(), e);
        }

        publishStkRealtime(push, true, "Order paid");
        log.info("Web order marked paid: orderId={} checkoutId={}", order.getId(), push.getGatewayCheckoutId());
    }

    private void confirmWalletIntent(GatewayStkPush push) {
        MpesaStkIntent intent = null;
        if (push.getContextId() != null) {
            intent = mpesaStkIntentRepository.findById(push.getContextId()).orElse(null);
        }
        if (intent == null) {
            intent = mpesaStkIntentRepository.findByBusinessIdAndIdempotencyKey(
                    push.getBusinessId(), push.getMerchantReference()).orElse(null);
        }
        if (intent == null) {
            log.warn("Wallet STK intent not found for push {}", push.getId());
            return;
        }
        if (MpesaStkStatuses.FULFILLED.equals(intent.getStatus())) {
            return;
        }
        try {
            CreditAccount acc = creditAccountRepository.findById(intent.getCreditAccountId()).orElseThrow();
            walletLedgerService.creditWalletFromMpesaStk(
                    intent.getBusinessId(),
                    acc.getCustomerId(),
                    intent.getAmount(),
                    intent.getId());
            intent.setStatus(MpesaStkStatuses.FULFILLED);
            intent.setGatewayConfirmationCode(
                    push.getGatewayTransactionId() != null ? push.getGatewayTransactionId() : "OK");
            intent.setFulfilledWalletTxnId(intent.getId());
            mpesaStkIntentRepository.save(intent);
            publishStkRealtime(push, true, "Wallet topped up");
        } catch (Exception e) {
            log.error("Failed to fulfill wallet STK intent {}", intent.getId(), e);
            markFailed(push, e.getMessage());
        }
    }

    private void publishPosConfirmation(GatewayStkPush push) {
        publishStkRealtime(push, true, "M-Pesa payment received");
    }

    private void publishStkRealtime(GatewayStkPush push, boolean success, String message) {
        eventPublisher.publishEvent(new RealtimeBridge.StkPaymentSettledEvent(
                push.getBusinessId(),
                push.getGatewayCheckoutId(),
                push.getMerchantReference(),
                push.getContextType().name(),
                push.getContextId(),
                success,
                message));
    }

    private PaymentGatewayConfig resolveConfig(GatewayStkPush push) {
        if (push.getConfigId() != null && !push.getConfigId().isBlank()) {
            PaymentGatewayConfig cfg = configRepository.findById(push.getConfigId()).orElse(null);
            if (cfg != null && push.getBusinessId().equals(cfg.getBusinessId())) {
                return cfg;
            }
        }
        return configRepository.findByBusinessIdAndGatewayTypeAndStatus(
                push.getBusinessId(), GatewayType.KOPOKOPO, GatewayStatus.ACTIVE)
                .stream()
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> decryptCredentials(PaymentGatewayConfig cfg) {
        try {
            String decrypted = encryptionService.decrypt(cfg.getCredentialsJson());
            return objectMapper.readValue(decrypted, Map.class);
        } catch (Exception e) {
            log.warn("Cannot decrypt credentials for STK poll config={}", cfg.getId());
            return null;
        }
    }
}
