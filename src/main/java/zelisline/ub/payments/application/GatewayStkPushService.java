package zelisline.ub.payments.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.MpesaStkStatuses;
import zelisline.ub.credits.application.BusinessCreditSettingsService;
import zelisline.ub.credits.application.CreditSaleDebtService;
import zelisline.ub.credits.application.CreditsJournalService;
import zelisline.ub.credits.application.CustomerPhoneOnPaymentService;
import zelisline.ub.credits.application.WalletLedgerService;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.MpesaStkIntent;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.MpesaStkIntentRepository;
import zelisline.ub.grocery.application.GroceryInvoiceService;
import zelisline.ub.notifications.application.NotificationOutboxService;
import zelisline.ub.payments.domain.GatewayStkPush;
import zelisline.ub.payments.domain.GatewayStkPushStatuses;
import zelisline.ub.payments.domain.GatewayStatus;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.PaymentWebhookEvent;
import zelisline.ub.payments.domain.PlatformKioskPaySettings;
import zelisline.ub.payments.domain.StkPushContextType;
import zelisline.ub.payments.domain.spi.WebhookResult;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.payments.infrastructure.KopokopoPaymentGateway;
import zelisline.ub.payments.repository.GatewayStkPushRepository;
import zelisline.ub.payments.repository.PaymentGatewayConfigRepository;
import zelisline.ub.payments.repository.PaymentWebhookEventRepository;
import zelisline.ub.messaging.application.CreditTabPaymentConfirmationEvent;
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

    public static final String TILL_AWAIT_CHECKOUT_PREFIX = "till-await-";
    public static final String STOREFRONT_TILL_AWAIT_PREFIX = "sf-till-await-";

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
    private final CreditSaleDebtService creditSaleDebtService;
    private final CreditsJournalService creditsJournalService;
    private final CustomerPhoneOnPaymentService customerPhoneOnPaymentService;
    private final CredentialEncryptionService encryptionService;
    private final KopokopoPaymentGateway kopokopoGateway;
    private final ObjectMapper objectMapper;
    private final NotificationOutboxService notificationOutboxService;
    private final WebOrderFulfillmentService webOrderFulfillmentService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final BusinessCreditSettingsService businessCreditSettingsService;
    private final ObjectProvider<GroceryInvoiceService> groceryInvoiceService;
    private final ObjectProvider<zelisline.ub.tenancy.application.DomainPurchaseService> domainPurchaseService;
    private final ObjectProvider<zelisline.ub.platform.application.PlatformDomainSettingsService> platformDomainSettingsService;
    private final ObjectProvider<PlatformKioskPaySettingsService> platformKioskPaySettingsService;
    private final ObjectProvider<KioskPayWalletService> kioskPayWalletService;
    private final InboundTillPaymentService inboundTillPaymentService;

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

    /**
     * Open a POS "waiting for buygoods till payment" slot (no STK prompt).
     * Returns empty when KopoKopo is not ACTIVE — callers must not treat that as fatal
     * (STK push remains available once the gateway is activated).
     */
    @Transactional
    public Optional<GatewayStkPush> registerTillAwait(
            String businessId,
            BigDecimal amount,
            String phoneNumber,
            String merchantReference
    ) {
        if (amount == null || amount.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount must be positive");
        }
        PaymentGatewayConfig cfg = configRepository
                .findByBusinessIdAndGatewayTypeAndStatus(businessId, GatewayType.KOPOKOPO, GatewayStatus.ACTIVE)
                .stream()
                .findFirst()
                .orElse(null);
        if (cfg == null) {
            log.info("Till await skipped — no ACTIVE KopoKopo for business={}", businessId);
            return Optional.empty();
        }

        // Replace prior open till-awaits for this business so amount collisions stay rare.
        Instant since = Instant.now().minus(Duration.ofMinutes(15));
        List<GatewayStkPush> open = pushRepository
                .findByBusinessIdAndContextTypeAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                        businessId,
                        StkPushContextType.POS_PAYMENT,
                        GatewayStkPushStatuses.PENDING,
                        since);
        for (GatewayStkPush prior : open) {
            if (isTillAwaitCheckout(prior.getGatewayCheckoutId())) {
                markFailed(prior, "Replaced by a new till payment wait");
            }
        }

        String checkoutId = TILL_AWAIT_CHECKOUT_PREFIX + java.util.UUID.randomUUID();
        String ref = merchantReference != null && !merchantReference.isBlank()
                ? merchantReference.trim()
                : checkoutId;
        GatewayStkPush push = registerPush(
                businessId,
                GatewayType.KOPOKOPO,
                cfg.getId(),
                checkoutId,
                ref,
                StkPushContextType.POS_PAYMENT,
                null,
                amount.setScale(2, RoundingMode.HALF_UP),
                phoneNumber != null ? phoneNumber : "");
        settleFromPendingInboundIfPresent(push);
        return Optional.of(push);
    }

    /**
     * Open a storefront Buy Goods await (cart preview / checkout). Soft-empty without ACTIVE KopoKopo.
     */
    @Transactional
    public Optional<GatewayStkPush> registerStorefrontTillAwait(
            String businessId,
            BigDecimal amount,
            String phoneNumber,
            String merchantReference
    ) {
        if (amount == null || amount.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount must be positive");
        }
        PaymentGatewayConfig cfg = configRepository
                .findByBusinessIdAndGatewayTypeAndStatus(businessId, GatewayType.KOPOKOPO, GatewayStatus.ACTIVE)
                .stream()
                .findFirst()
                .orElse(null);
        if (cfg == null) {
            log.info("Storefront till await skipped — no ACTIVE KopoKopo for business={}", businessId);
            return Optional.empty();
        }

        Instant since = Instant.now().minus(Duration.ofMinutes(15));
        List<GatewayStkPush> open = pushRepository
                .findByBusinessIdAndContextTypeAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                        businessId,
                        StkPushContextType.STOREFRONT_CART,
                        GatewayStkPushStatuses.PENDING,
                        since);
        for (GatewayStkPush prior : open) {
            if (isStorefrontTillAwaitCheckout(prior.getGatewayCheckoutId())) {
                markFailed(prior, "Replaced by a new storefront till payment wait");
            }
        }

        String checkoutId = STOREFRONT_TILL_AWAIT_PREFIX + java.util.UUID.randomUUID();
        String ref = merchantReference != null && !merchantReference.isBlank()
                ? merchantReference.trim()
                : checkoutId;
        GatewayStkPush push = registerPush(
                businessId,
                GatewayType.KOPOKOPO,
                cfg.getId(),
                checkoutId,
                ref,
                StkPushContextType.STOREFRONT_CART,
                null,
                amount.setScale(2, RoundingMode.HALF_UP),
                phoneNumber != null ? phoneNumber : "");
        settleFromPendingInboundIfPresent(push);
        return Optional.of(push);
    }

    public static boolean isTillAwaitCheckout(String gatewayCheckoutId) {
        return gatewayCheckoutId != null && gatewayCheckoutId.startsWith(TILL_AWAIT_CHECKOUT_PREFIX);
    }

    public static boolean isStorefrontTillAwaitCheckout(String gatewayCheckoutId) {
        return gatewayCheckoutId != null && gatewayCheckoutId.startsWith(STOREFRONT_TILL_AWAIT_PREFIX);
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
        if (eventId != null && !eventId.isBlank()
                && webhookEventRepository.existsByGatewayTypeAndGatewayEventId(GatewayType.KOPOKOPO, eventId)) {
            log.info("KopoKopo webhook duplicate ignored: eventId={}", eventId);
            return true;
        }

        Optional<GatewayStkPush> push = resolvePush(businessId, parsed);
        if (push.isEmpty()) {
            // Persist successful buygoods for late-bind (till-await race, sale, or exact claim).
            // Do not write payment_webhook_events yet — retries can still match a later await.
            log.warn("KopoKopo webhook: no matching STK push business={} checkout={} ref={} topic={} amount={}",
                    businessId, parsed.gatewayCheckoutId(), parsed.reference(),
                    parsed.topic(), parsed.amount());
            if (parsed.success()
                    && parsed.topic() != null
                    && parsed.topic().equalsIgnoreCase("buygoods_transaction_received")) {
                inboundTillPaymentService.persistUnmatchedBuygoods(businessId, parsed)
                        .ifPresent(inbound -> {
                            String receipt = inbound.getMpesaReceipt() != null
                                    ? inbound.getMpesaReceipt()
                                    : parsed.gatewayTransactionId();
                            inboundTillPaymentService.tryAutoApproveClaimByReceipt(
                                    businessId, receipt, inbound);
                        });
            }
            return true;
        }

        return settleMatchedWebhook(push.get(), businessId, eventId, parsed);
    }

    /**
     * Webhook authenticated with Palmart platform KopoKopo credentials (domain-order STK).
     * Resolves the push by checkout id globally (business id comes from the push row).
     */
    @Transactional
    public boolean processPlatformKopokopoWebhook(WebhookResult parsed) {
        if (parsed == null) {
            return false;
        }
        String eventId = parsed.webhookEventId() != null && !parsed.webhookEventId().isBlank()
                ? parsed.webhookEventId()
                : parsed.gatewayTransactionId();
        if (eventId != null && !eventId.isBlank()
                && webhookEventRepository.existsByGatewayTypeAndGatewayEventId(GatewayType.KOPOKOPO, eventId)) {
            log.info("KopoKopo platform webhook duplicate ignored: eventId={}", eventId);
            return true;
        }

        Optional<GatewayStkPush> push = Optional.empty();
        if (parsed.gatewayCheckoutId() != null && !parsed.gatewayCheckoutId().isBlank()) {
            push = pushRepository.findByGatewayTypeAndGatewayCheckoutId(
                    GatewayType.KOPOKOPO, parsed.gatewayCheckoutId().trim());
        }
        if (push.isEmpty() && parsed.reference() != null && !parsed.reference().isBlank()) {
            push = pushRepository.findFirstByMerchantReferenceAndStatusAndContextType(
                    parsed.reference().trim(),
                    GatewayStkPushStatuses.PENDING,
                    StkPushContextType.DOMAIN_ORDER);
        }
        if (push.isEmpty()
                || push.get().getContextType() != StkPushContextType.DOMAIN_ORDER) {
            log.warn("KopoKopo platform webhook: no DOMAIN_ORDER push checkout={} ref={}",
                    parsed.gatewayCheckoutId(), parsed.reference());
            return true;
        }

        return settleMatchedWebhook(push.get(), push.get().getBusinessId(), eventId, parsed);
    }

    /**
     * Webhook authenticated with platform Kiosk Pay KopoKopo credentials (POS STK + withdraw).
     */
    @Transactional
    public boolean processKioskPayKopokopoWebhook(WebhookResult parsed) {
        if (parsed == null) {
            return false;
        }
        String eventId = parsed.webhookEventId() != null && !parsed.webhookEventId().isBlank()
                ? parsed.webhookEventId()
                : parsed.gatewayTransactionId();
        if (eventId != null && !eventId.isBlank()
                && webhookEventRepository.existsByGatewayTypeAndGatewayEventId(GatewayType.KOPOKOPO, eventId)) {
            log.info("KopoKopo Kiosk Pay webhook duplicate ignored: eventId={}", eventId);
            return true;
        }

        Optional<GatewayStkPush> push = Optional.empty();
        if (parsed.gatewayCheckoutId() != null && !parsed.gatewayCheckoutId().isBlank()) {
            push = pushRepository.findByGatewayTypeAndGatewayCheckoutId(
                    GatewayType.KOPOKOPO, parsed.gatewayCheckoutId().trim());
        }
        if (push.isEmpty() || !PlatformKioskPaySettings.PLATFORM_KOPOKOPO_CONFIG_ID.equals(push.get().getConfigId())) {
            log.warn("KopoKopo Kiosk Pay webhook: no platform push checkout={}", parsed.gatewayCheckoutId());
            return true;
        }
        return settleMatchedWebhook(push.get(), push.get().getBusinessId(), eventId, parsed);
    }

    private boolean settleMatchedWebhook(
            GatewayStkPush push,
            String businessId,
            String eventId,
            WebhookResult parsed
    ) {
        if (eventId != null && !eventId.isBlank()) {
            try {
                PaymentWebhookEvent audit = new PaymentWebhookEvent();
                audit.setBusinessId(businessId);
                audit.setGatewayType(GatewayType.KOPOKOPO);
                audit.setGatewayEventId(eventId);
                audit.setTopic(parsed.topic());
                audit.setRawPayload(parsed.rawPayload());
                webhookEventRepository.save(audit);
            } catch (DataIntegrityViolationException e) {
                log.info("KopoKopo webhook duplicate on save ignored: eventId={}", eventId);
                return true;
            }
        }

        if (parsed.success()) {
            confirmPush(push, parsed.gatewayTransactionId(), parsed.amount());
            String receipt = parsed.gatewayTransactionId() != null
                    ? parsed.gatewayTransactionId()
                    : parsed.reference();
            inboundTillPaymentService.linkPendingByReceiptToPush(
                    businessId, receipt, push.getId());
            if (parsed.topic() != null
                    && parsed.topic().equalsIgnoreCase("buygoods_transaction_received")) {
                inboundTillPaymentService.tryAutoApproveClaimByReceipt(businessId, receipt, null);
            }
            return true;
        }
        if (parsed.terminalFailure()) {
            markFailed(push, "Payment declined by M-Pesa");
            return true;
        }
        return true;
    }

    /**
     * Race fix: buygoods arrived before the till-await row existed.
     */
    private void settleFromPendingInboundIfPresent(GatewayStkPush push) {
        if (push == null || !GatewayStkPushStatuses.PENDING.equals(push.getStatus())) {
            return;
        }
        inboundTillPaymentService
                .findClearPendingMatch(push.getBusinessId(), push.getAmount(), push.getPhoneNumber())
                .ifPresent(inbound -> {
                    String receipt = inbound.getMpesaReceipt();
                    if (receipt == null || receipt.isBlank()) {
                        return;
                    }
                    log.info("Late-binding till-await push={} to inbound={} receipt={}",
                            push.getId(), inbound.getId(), receipt);
                    confirmPush(push, receipt, inbound.getAmount());
                    inboundTillPaymentService.markLinkedToPush(inbound, push.getId());
                    inboundTillPaymentService.tryAutoApproveClaimByReceipt(
                            push.getBusinessId(), receipt, inbound);
                });
    }

    @Transactional
    public Optional<GatewayStkPush> pollAndUpdate(GatewayStkPush push) {
        if (!GatewayStkPushStatuses.PENDING.equals(push.getStatus())) {
            return Optional.of(push);
        }
        if (push.getGatewayType() != GatewayType.KOPOKOPO) {
            return Optional.of(push);
        }
        // Till-awaits have no KopoKopo incoming_payment id — only buygoods webhooks settle them.
        if (isTillAwaitCheckout(push.getGatewayCheckoutId())) {
            return Optional.of(push);
        }
        Map<String, String> creds = resolveCredentialsForPush(push);
        if (creds == null || creds.isEmpty()) {
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
        // After this age, a gateway "Pending" on an already-failed local row is treated as a
        // stale status ghost — the phone UI is usually gone even if KopoKopo is slow to flip.
        Instant ghostPendingBefore = Instant.now().minus(75, ChronoUnit.SECONDS);
        List<GatewayStkPush> recent = pushRepository
                .findByBusinessIdAndPhoneNumberAndCreatedAtAfterOrderByCreatedAtDesc(
                        businessId, phone, since);
        int terminalUpdates = 0;
        boolean gatewayPending = false;
        boolean gatewayJustFailed = false;
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
                gatewayJustFailed = true;
            } else {
                // Still Pending (or transient query error treated as non-terminal).
                boolean staleGhost = !localPending
                        && push.getCreatedAt() != null
                        && push.getCreatedAt().isBefore(ghostPendingBefore);
                if (staleGhost) {
                    log.info(
                            "Ignoring stale Pending gateway status for failed STK pushId={} phone={}",
                            push.getId(),
                            phone);
                } else {
                    gatewayPending = true;
                }
            }
        }
        // Always expire our local stale rows so cashiers can retry even while
        // Safaricom's MSISDN lock is still draining.
        expireStalePendingForPhone(businessId, phone);
        boolean stillLocalPending = pushRepository
                .findByBusinessIdAndPhoneNumberAndStatusAndCreatedAtAfterOrderByCreatedAtAsc(
                        businessId, phone, GatewayStkPushStatuses.PENDING, since)
                .stream()
                .anyMatch(p -> GatewayStkPushStatuses.PENDING.equals(p.getStatus()));
        if (stillLocalPending) {
            gatewayPending = true;
        }
        return new PhoneClearResult(gatewayPending, terminalUpdates, gatewayJustFailed);
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
        return "Safaricom is still holding a lock on this phone from the previous prompt "
                + "(the PIN screen can already be gone). Wait about a minute, then send again.";
    }

    public record ReconcileResult(int terminalUpdates, boolean hasOpenPending) {
    }

    public record PhoneClearResult(boolean hasGatewayPending, int terminalUpdates, boolean gatewayJustFailed) {
        public PhoneClearResult(boolean hasGatewayPending, int terminalUpdates) {
            this(hasGatewayPending, terminalUpdates, false);
        }
    }

    @Transactional
    public void markTimedOutIfPollsExhausted(GatewayStkPush push, int maxPolls) {
        if (push == null || maxPolls <= 0) {
            return;
        }
        if (push.getPollCount() >= maxPolls
                && GatewayStkPushStatuses.PENDING.equals(push.getStatus())
                && !isTillAwaitCheckout(push.getGatewayCheckoutId())) {
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
            Optional<GatewayStkPush> byMerchantRef = pushRepository
                    .findFirstByBusinessIdAndMerchantReferenceAndStatus(
                            businessId, parsed.reference().trim(), GatewayStkPushStatuses.PENDING);
            if (byMerchantRef.isPresent()) {
                return byMerchantRef;
            }
        }
        // Buygoods / till payments: match open POS STK by phone + amount (receipt ≠ merchant ref).
        return resolvePosBuygoodsPush(businessId, parsed);
    }

    /**
     * Match an unmatched till webhook to a pending POS STK push.
     * Prefers phone+amount; falls back to unique amount-only within the window.
     * Only runs for {@code buygoods_transaction_received} — never amount-match
     * other topics (avoids confirming POS from unrelated callbacks).
     */
    private Optional<GatewayStkPush> resolvePosBuygoodsPush(String businessId, WebhookResult parsed) {
        if (!parsed.success() || parsed.amount() == null) {
            return Optional.empty();
        }
        if (parsed.topic() == null
                || !parsed.topic().equalsIgnoreCase("buygoods_transaction_received")) {
            return Optional.empty();
        }

        // Window for matching Buy Goods to an open till-await / STK (cashier + storefront).
        Instant since = Instant.now().minus(Duration.ofMinutes(10));
        List<GatewayStkPush> pending = new java.util.ArrayList<>();
        pending.addAll(pushRepository
                .findByBusinessIdAndContextTypeAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                        businessId,
                        StkPushContextType.POS_PAYMENT,
                        GatewayStkPushStatuses.PENDING,
                        since));
        pending.addAll(pushRepository
                .findByBusinessIdAndContextTypeAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                        businessId,
                        StkPushContextType.STOREFRONT_CART,
                        GatewayStkPushStatuses.PENDING,
                        since));
        // Prefer most recent overall (both lists are DESC; merge by createdAt).
        pending.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        if (pending.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal amount = parsed.amount().setScale(2, RoundingMode.HALF_UP);
        String phone = StkPhoneNormalizer.normalize(parsed.phoneNumber());

        List<GatewayStkPush> amountMatches = pending.stream()
                .filter(p -> amountsClose(p.getAmount(), amount))
                .toList();
        if (amountMatches.isEmpty()) {
            log.info("Buygoods webhook: no pending POS amount match business={} amount={}",
                    businessId, amount);
            return Optional.empty();
        }

        if (phone != null) {
            List<GatewayStkPush> phoneMatches = amountMatches.stream()
                    .filter(p -> phone.equals(StkPhoneNormalizer.normalize(p.getPhoneNumber())))
                    .toList();
            if (phoneMatches.size() == 1) {
                return Optional.of(phoneMatches.get(0));
            }
            if (phoneMatches.size() > 1) {
                // Most recent phone+amount match (list is createdAt DESC).
                log.info("Buygoods webhook: multiple phone+amount matches — using most recent pushId={}",
                        phoneMatches.get(0).getId());
                return Optional.of(phoneMatches.get(0));
            }
        }

        if (amountMatches.size() == 1) {
            return Optional.of(amountMatches.get(0));
        }

        log.warn("Buygoods webhook: ambiguous amount match business={} amount={} candidates={} — skipping auto-confirm",
                businessId, amount, amountMatches.size());
        return Optional.empty();
    }

    private static boolean amountsClose(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return false;
        }
        return a.subtract(b).abs().compareTo(new BigDecimal("1.00")) <= 0;
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
            case CREDIT_AR -> confirmCreditArIntent(push);
            case POS_PAYMENT -> {
                creditKioskPayIfPlatformStk(push);
                publishPosConfirmation(push);
            }
            case STOREFRONT_CART -> log.info(
                    "Storefront till payment confirmed push={} txn={}",
                    push.getId(), gatewayTxnId);
            case GROCERY_INVOICE -> confirmGroceryInvoice(push);
            case DOMAIN_ORDER -> confirmDomainOrder(push);
            default -> log.warn("Unknown STK context type: {}", push.getContextType());
        }
    }

    private void creditKioskPayIfPlatformStk(GatewayStkPush push) {
        if (!PlatformKioskPaySettings.PLATFORM_KOPOKOPO_CONFIG_ID.equals(push.getConfigId())) {
            return;
        }
        KioskPayWalletService wallet = kioskPayWalletService.getIfAvailable();
        if (wallet == null) {
            return;
        }
        try {
            wallet.creditPaymentCapture(
                    push.getBusinessId(),
                    push.getAmount(),
                    "KES",
                    "kp-stk-" + push.getGatewayCheckoutId(),
                    push.getContextType().name(),
                    push.getContextId(),
                    push.getGatewayCheckoutId());
        } catch (Exception e) {
            log.error("Kiosk Pay wallet credit failed for STK push={}", push.getId(), e);
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
        if (push.getContextType() == StkPushContextType.WALLET_INTENT
                || push.getContextType() == StkPushContextType.CREDIT_AR) {
            mpesaStkIntentRepository.findByBusinessIdAndIdempotencyKey(
                    push.getBusinessId(), push.getMerchantReference()).ifPresent(intent -> {
                if (MpesaStkStatuses.PENDING.equals(intent.getStatus())) {
                    intent.setStatus(MpesaStkStatuses.FAILED);
                    mpesaStkIntentRepository.save(intent);
                }
            });
        }
        if (push.getContextType() == StkPushContextType.GROCERY_INVOICE && push.getContextId() != null) {
            try {
                GroceryInvoiceService grocery = groceryInvoiceService.getIfAvailable();
                if (grocery != null) {
                    grocery.markRemoteStkFailed(push.getBusinessId(), push.getContextId(), reason);
                }
            } catch (Exception e) {
                log.warn("Failed to mark remote grocery invoice STK failed {}", push.getContextId(), e);
            }
        }
        if (push.getContextType() == StkPushContextType.DOMAIN_ORDER && push.getContextId() != null) {
            try {
                var purchase = domainPurchaseService.getIfAvailable();
                if (purchase != null) {
                    purchase.markStkFailed(push.getBusinessId(), push.getContextId(), reason);
                }
            } catch (Exception e) {
                log.warn("Failed to mark domain order STK failed {}", push.getContextId(), e);
            }
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
        MpesaStkIntent intent = resolveIntent(push);
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

    private void confirmCreditArIntent(GatewayStkPush push) {
        MpesaStkIntent intent = resolveIntent(push);
        if (intent == null) {
            log.warn("Credit AR STK intent not found for push {}", push.getId());
            return;
        }
        if (MpesaStkStatuses.FULFILLED.equals(intent.getStatus())) {
            return;
        }
        try {
            BigDecimal pay = intent.getAmount();
            creditSaleDebtService.applyInboundArPayment(
                    intent.getBusinessId(), intent.getCreditAccountId(), pay);
            creditsJournalService.postInboundMpesaTowardAr(
                    intent.getBusinessId(),
                    pay,
                    intent.getId(),
                    "M-Pesa STK tab payment");
            intent.setStatus(MpesaStkStatuses.FULFILLED);
            intent.setGatewayConfirmationCode(
                    push.getGatewayTransactionId() != null ? push.getGatewayTransactionId() : "OK");
            mpesaStkIntentRepository.save(intent);

            CreditAccount acc = creditAccountRepository.findById(intent.getCreditAccountId()).orElse(null);
            if (acc != null) {
                String paidPhone = intent.getStkPhone();
                if (paidPhone == null || paidPhone.isBlank()) {
                    paidPhone = push.getPhoneNumber();
                }
                customerPhoneOnPaymentService.syncPrimaryPhoneAfterPayment(
                        intent.getBusinessId(), acc.getCustomerId(), paidPhone);

                String phoneDigits = StkPhoneNormalizer.normalize(paidPhone);
                eventPublisher.publishEvent(new CreditTabPaymentConfirmationEvent(
                        intent.getBusinessId(),
                        intent.getId(),
                        acc.getCustomerId(),
                        pay,
                        acc.getBalanceOwed(),
                        phoneDigits));
            }

            publishStkRealtime(push, true, "Tab payment received");
        } catch (Exception e) {
            log.error("Failed to fulfill credit AR STK intent {}", intent.getId(), e);
            markFailed(push, e.getMessage());
        }
    }

    private MpesaStkIntent resolveIntent(GatewayStkPush push) {
        MpesaStkIntent intent = null;
        if (push.getContextId() != null) {
            intent = mpesaStkIntentRepository.findById(push.getContextId()).orElse(null);
        }
        if (intent == null) {
            intent = mpesaStkIntentRepository.findByBusinessIdAndIdempotencyKey(
                    push.getBusinessId(), push.getMerchantReference()).orElse(null);
        }
        return intent;
    }

    private void publishPosConfirmation(GatewayStkPush push) {
        publishStkRealtime(push, true, "M-Pesa payment received");
    }

    private void confirmGroceryInvoice(GatewayStkPush push) {
        if (push.getContextId() == null) {
            return;
        }
        GroceryInvoiceService grocery = groceryInvoiceService.getIfAvailable();
        if (grocery == null) {
            log.warn("GroceryInvoiceService unavailable for STK settle {}", push.getId());
            return;
        }
        boolean autoSettle = businessCreditSettingsService
                .resolveForBusiness(push.getBusinessId())
                .isRemoteInvoiceStkAutoSettle();
        try {
            boolean settled = grocery.settleRemoteInvoiceFromStk(
                    push.getBusinessId(),
                    push.getContextId(),
                    push.getGatewayTransactionId(),
                    autoSettle);
            publishStkRealtime(
                    push,
                    true,
                    settled && autoSettle
                            ? "Remote bill paid"
                            : "M-Pesa received — clear the bill on the till");
            log.info("Remote grocery invoice STK settled invoice={} autoSettle={} paid={}",
                    push.getContextId(), autoSettle, settled && autoSettle);
        } catch (Exception e) {
            log.error("Failed to settle remote grocery invoice {}", push.getContextId(), e);
            markFailed(push, e.getMessage());
        }
    }

    private void confirmDomainOrder(GatewayStkPush push) {
        if (push.getContextId() == null) {
            return;
        }
        try {
            var purchase = domainPurchaseService.getIfAvailable();
            if (purchase == null) {
                log.warn("DomainPurchaseService unavailable for STK settle {}", push.getId());
                return;
            }
            purchase.settleFromStk(
                    push.getBusinessId(),
                    push.getContextId(),
                    push.getGatewayCheckoutId(),
                    push.getGatewayTransactionId(),
                    push.getPhoneNumber());
            publishStkRealtime(push, true, "Domain order paid");
            log.info("Domain order STK settled order={} txn={}",
                    push.getContextId(), push.getGatewayTransactionId());
        } catch (Exception e) {
            log.error("Failed to settle domain order {}", push.getContextId(), e);
            markFailed(push, e.getMessage());
        }
    }

    private void publishStkRealtime(GatewayStkPush push, boolean success, String message) {
        eventPublisher.publishEvent(new RealtimeBridge.StkPaymentSettledEvent(
                push.getBusinessId(),
                push.getGatewayCheckoutId(),
                push.getMerchantReference(),
                push.getContextType().name(),
                push.getContextId(),
                success,
                message,
                push.getGatewayTransactionId()));
    }

    private PaymentGatewayConfig resolveConfig(GatewayStkPush push) {
        if (push.getConfigId() != null && !push.getConfigId().isBlank()) {
            if (zelisline.ub.platform.application.PlatformDomainSettingsService.PLATFORM_DOMAIN_STK_CONFIG_ID
                    .equals(push.getConfigId())
                    || PlatformKioskPaySettings.PLATFORM_KOPOKOPO_CONFIG_ID.equals(push.getConfigId())) {
                return null;
            }
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

    private Map<String, String> resolveCredentialsForPush(GatewayStkPush push) {
        if (push.getConfigId() != null
                && zelisline.ub.platform.application.PlatformDomainSettingsService.PLATFORM_DOMAIN_STK_CONFIG_ID
                        .equals(push.getConfigId())) {
            var settings = platformDomainSettingsService.getIfAvailable();
            if (settings == null) {
                return null;
            }
            Map<String, String> creds = settings.resolvePalmartStkCredentials();
            return creds.isEmpty() ? null : creds;
        }
        if (push.getConfigId() != null
                && PlatformKioskPaySettings.PLATFORM_KOPOKOPO_CONFIG_ID.equals(push.getConfigId())) {
            PlatformKioskPaySettingsService kiosk = platformKioskPaySettingsService.getIfAvailable();
            if (kiosk == null) {
                return null;
            }
            return kiosk.kopokopoCredentials().orElse(null);
        }
        PaymentGatewayConfig cfg = resolveConfig(push);
        if (cfg == null) {
            return null;
        }
        return decryptCredentials(cfg);
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
