package zelisline.ub.payments.application;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.notifications.application.NotificationOutboxService;
import zelisline.ub.payments.api.dto.GatewayCheckoutResponse;
import zelisline.ub.payments.domain.GatewayCheckout;
import zelisline.ub.payments.domain.GatewayCheckoutContextType;
import zelisline.ub.payments.domain.GatewayCheckoutStatuses;
import zelisline.ub.payments.domain.GatewayStatus;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.PaymentWebhookEvent;
import zelisline.ub.payments.domain.PlatformKioskPaySettings;
import zelisline.ub.payments.domain.spi.CheckoutPaymentGateway;
import zelisline.ub.payments.domain.spi.CheckoutRequest;
import zelisline.ub.payments.domain.spi.CheckoutResponse;
import zelisline.ub.payments.domain.spi.VerifyTransactionRequest;
import zelisline.ub.payments.domain.spi.VerifyTransactionResponse;
import zelisline.ub.payments.domain.spi.WebhookResult;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.payments.repository.GatewayCheckoutRepository;
import zelisline.ub.payments.repository.PaymentGatewayConfigRepository;
import zelisline.ub.payments.repository.PaymentWebhookEventRepository;
import zelisline.ub.storefront.WebOrderStatuses;
import zelisline.ub.storefront.application.WebOrderFulfillmentService;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.repository.WebOrderRepository;
import zelisline.ub.tenancy.domain.DomainMapping;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

import org.springframework.beans.factory.ObjectProvider;

/**
 * Orchestrates provider-hosted checkout attempts ({@code gateway_checkouts}).
 *
 * <p>Responsible for: initializing a checkout against the tenant's ACTIVE
 * Paystack config, settling webhooks (resolve-by-reference → verify → dedupe),
 * server-side verification, and fanning success into domain entities
 * (Phase 1: {@link WebOrder}).
 */
@Service
@RequiredArgsConstructor
public class GatewayCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(GatewayCheckoutService.class);

    /** Max |provider amount − stored amount| before we refuse to confirm. */
    private static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("1.00");

    private final GatewayCheckoutRepository checkoutRepository;
    private final PaymentGatewayConfigRepository configRepository;
    private final PlatformPaymentGatewayService platformPaymentGatewayService;
    private final PaymentGatewayRegistry gatewayRegistry;
    private final CredentialEncryptionService encryptionService;
    private final PaymentWebhookEventRepository webhookEventRepository;
    private final WebOrderRepository webOrderRepository;
    private final WebOrderFulfillmentService webOrderFulfillmentService;
    private final NotificationOutboxService notificationOutboxService;
    private final DomainMappingRepository domainMappingRepository;
    private final ObjectProvider<KioskPayWalletService> kioskPayWalletService;
    private final ObjectProvider<PlatformKioskPaySettingsService> platformKioskPaySettingsService;
    private final ObjectMapper objectMapper;

    @Value("${app.public.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    @Value("${app.tenancy.platform-hosts:kiosk.ke,www.kiosk.ke}")
    private String platformHostsCsv;

    // ── Initiation ───────────────────────────────────────────────────

    /**
     * Initialize a hosted checkout for a web order against the business's
     * ACTIVE Paystack config. Persists the PENDING row first so an immediate
     * webhook can still resolve it.
     *
     * @param returnOrigin optional browser origin ({@code window.location.origin})
     *                     so custom-domain shoppers return to the same host
     */
    @Transactional
    public CheckoutInitiation initiateWebOrderCheckout(
            String businessId,
            String businessSlug,
            String orderId,
            String preferredConfigId,
            String email,
            String returnOrigin
    ) {
        WebOrder order = webOrderRepository.findById(orderId).orElse(null);
        if (order == null || !businessId.equals(order.getBusinessId())) {
            return CheckoutInitiation.rejected(null, null, "Order not found");
        }
        if (!WebOrderStatuses.PENDING_PAYMENT.equals(order.getStatus())
                && !WebOrderStatuses.PAYMENT_FAILED.equals(order.getStatus())) {
            return CheckoutInitiation.rejected(null, null, "Order is not awaiting payment");
        }

        PaymentGatewayConfig cfg = resolvePaystackConfig(businessId, preferredConfigId);
        if (cfg == null || !(gatewayRegistry.get(cfg.getGatewayType().name()) instanceof CheckoutPaymentGateway gateway)) {
            return CheckoutInitiation.rejected(null, null, "Paystack is not available for this store right now.");
        }

        String reference = buildReference(cfg.getId(), order.getId());
        String callbackUrl = buildCallbackUrl(businessId, businessSlug, returnOrigin, order.getId());
        String customerEmail = resolveEmail(email, order);

        GatewayCheckout row = new GatewayCheckout();
        row.setBusinessId(businessId);
        row.setGatewayType(cfg.getGatewayType());
        row.setConfigId(cfg.getId());
        row.setReference(reference);
        row.setContextType(GatewayCheckoutContextType.WEB_ORDER);
        row.setContextId(order.getId());
        row.setAmount(order.getGrandTotal());
        row.setCurrency(order.getCurrency() != null && !order.getCurrency().isBlank()
                ? order.getCurrency()
                : "KES");
        row.setCustomerEmail(customerEmail);
        row.setStatus(GatewayCheckoutStatuses.PENDING);
        row.setMetadataJson(toJson(routingMetadata(businessId, cfg.getId(), order.getId())));
        checkoutRepository.save(row);

        Map<String, String> creds;
        try {
            creds = decryptCredentials(cfg);
        } catch (Exception e) {
            log.warn("Paystack checkout: cannot read credentials for config={}", cfg.getId(), e);
            markFailed(row, "Credentials could not be read");
            return CheckoutInitiation.rejected(row.getId(), reference,
                    "Paystack credentials are invalid. Ask the store to re-save them.");
        }
        CheckoutResponse response;
        try {
            response = gateway.initializeCheckout(new CheckoutRequest(
                    businessId,
                    cfg.getId(),
                    order.getGrandTotal(),
                    row.getCurrency(),
                    customerEmail,
                    reference,
                    "Web order " + order.getId(),
                    callbackUrl,
                    routingMetadata(businessId, cfg.getId(), order.getId()),
                    creds));
        } catch (Exception e) {
            log.error("Paystack checkout init threw for order={}", order.getId(), e);
            markFailed(row, "Checkout initialization failed");
            return CheckoutInitiation.rejected(row.getId(), reference,
                    "Could not start Paystack checkout. Please try again.");
        }

        if (!response.accepted()) {
            log.warn("Paystack checkout rejected for order={}: {} {}",
                    order.getId(), response.responseCode(), response.responseDescription());
            markFailed(row, response.responseDescription() != null
                    ? response.responseDescription()
                    : "Checkout initialization rejected");
            return CheckoutInitiation.rejected(row.getId(), reference,
                    "Could not start Paystack checkout. Please try again.");
        }

        row.setAuthorizationUrl(response.authorizationUrl());
        row.setAccessCode(response.accessCode());
        row.setProviderTransactionId(response.providerTransactionId());
        row.setFailureReason(null);
        checkoutRepository.save(row);
        log.info("Paystack checkout initialized: order={} ref={}", order.getId(), reference);
        return CheckoutInitiation.accepted(
                row.getId(), reference, GatewayCheckoutStatuses.PENDING, response.authorizationUrl(), null);
    }

    /**
     * Initialize hosted checkout using <strong>platform</strong> Paystack credentials
     * (Kiosk Pay custody). Credits the tenant wallet on verified success.
     */
    @Transactional
    public CheckoutInitiation initiateKioskPayWebOrderCheckout(
            String businessId,
            String businessSlug,
            String orderId,
            String email,
            String returnOrigin
    ) {
        KioskPayWalletService wallet = kioskPayWalletService.getIfAvailable();
        PlatformKioskPaySettingsService kioskSettings = platformKioskPaySettingsService.getIfAvailable();
        if (wallet == null || kioskSettings == null || !wallet.isStorefrontCollectEnabled(businessId)) {
            return CheckoutInitiation.rejected(null, null, "Kiosk Pay is not available for this store.");
        }
        Map<String, String> creds = kioskSettings.paystackCredentials().orElse(null);
        if (creds == null || creds.isEmpty()) {
            return CheckoutInitiation.rejected(null, null, "Kiosk Pay is temporarily unavailable.");
        }
        if (!(gatewayRegistry.get(GatewayType.PAYSTACK.name()) instanceof CheckoutPaymentGateway gateway)) {
            return CheckoutInitiation.rejected(null, null, "Paystack is not available.");
        }

        WebOrder order = webOrderRepository.findById(orderId).orElse(null);
        if (order == null || !businessId.equals(order.getBusinessId())) {
            return CheckoutInitiation.rejected(null, null, "Order not found");
        }
        if (!WebOrderStatuses.PENDING_PAYMENT.equals(order.getStatus())
                && !WebOrderStatuses.PAYMENT_FAILED.equals(order.getStatus())) {
            return CheckoutInitiation.rejected(null, null, "Order is not awaiting payment");
        }

        String configId = PlatformKioskPaySettings.PLATFORM_PAYSTACK_CONFIG_ID;
        String reference = buildReference(configId, order.getId());
        String callbackUrl = buildCallbackUrl(businessId, businessSlug, returnOrigin, order.getId());
        String customerEmail = resolveEmail(email, order);

        GatewayCheckout row = new GatewayCheckout();
        row.setBusinessId(businessId);
        row.setGatewayType(GatewayType.PAYSTACK);
        row.setConfigId(configId);
        row.setReference(reference);
        row.setContextType(GatewayCheckoutContextType.WEB_ORDER);
        row.setContextId(order.getId());
        row.setAmount(order.getGrandTotal());
        row.setCurrency(order.getCurrency() != null && !order.getCurrency().isBlank()
                ? order.getCurrency()
                : "KES");
        row.setCustomerEmail(customerEmail);
        row.setStatus(GatewayCheckoutStatuses.PENDING);
        Map<String, String> meta = routingMetadata(businessId, configId, order.getId());
        meta.put("kioskPay", "true");
        row.setMetadataJson(toJson(meta));
        checkoutRepository.save(row);

        CheckoutResponse response;
        try {
            response = gateway.initializeCheckout(new CheckoutRequest(
                    businessId,
                    configId,
                    order.getGrandTotal(),
                    row.getCurrency(),
                    customerEmail,
                    reference,
                    "Web order " + order.getId() + " (Kiosk Pay)",
                    callbackUrl,
                    meta,
                    creds));
        } catch (Exception e) {
            log.error("Kiosk Pay checkout init threw for order={}", order.getId(), e);
            markFailed(row, "Checkout initialization failed");
            return CheckoutInitiation.rejected(row.getId(), reference,
                    "Could not start Kiosk Pay checkout. Please try again.");
        }

        if (!response.accepted()) {
            markFailed(row, response.responseDescription() != null
                    ? response.responseDescription()
                    : "Checkout initialization rejected");
            return CheckoutInitiation.rejected(row.getId(), reference,
                    "Could not start Kiosk Pay checkout. Please try again.");
        }

        row.setAuthorizationUrl(response.authorizationUrl());
        row.setAccessCode(response.accessCode());
        row.setProviderTransactionId(response.providerTransactionId());
        row.setFailureReason(null);
        checkoutRepository.save(row);
        log.info("Kiosk Pay checkout initialized: order={} ref={}", order.getId(), reference);
        return CheckoutInitiation.accepted(
                row.getId(), reference, GatewayCheckoutStatuses.PENDING, response.authorizationUrl(), null);
    }

    // ── Webhook settlement ───────────────────────────────────────────

    /**
     * Settle a signature-verified webhook. Idempotent via
     * {@code payment_webhook_events} (gateway event id = Paystack data.id).
     *
     * <p>On success events we always re-verify with the Paystack API before
     * confirming — the signed payload alone is not enough to mark paid.
     */
    @Transactional
    public boolean handleWebhook(String businessId, String configId, WebhookResult parsed) {
        if (parsed == null) {
            return false;
        }
        String eventId = parsed.webhookEventId() != null && !parsed.webhookEventId().isBlank()
                ? parsed.webhookEventId()
                : parsed.gatewayTransactionId();
        if (eventId != null && !eventId.isBlank()
                && webhookEventRepository.existsByGatewayTypeAndGatewayEventId(GatewayType.PAYSTACK, eventId)) {
            log.info("Paystack webhook duplicate ignored: eventId={}", eventId);
            return true;
        }

        Optional<GatewayCheckout> checkoutOpt = checkoutRepository.findByReference(parsed.reference());
        if (checkoutOpt.isEmpty()) {
            log.warn("Paystack webhook: no matching checkout ref={} topic={}",
                    parsed.reference(), parsed.topic());
            return true;
        }
        GatewayCheckout checkout = checkoutOpt.get();

        if (eventId != null && !eventId.isBlank()) {
            try {
                PaymentWebhookEvent audit = new PaymentWebhookEvent();
                audit.setBusinessId(businessId);
                audit.setGatewayType(GatewayType.PAYSTACK);
                audit.setGatewayEventId(eventId);
                audit.setTopic(parsed.topic());
                audit.setRawPayload(parsed.rawPayload());
                webhookEventRepository.save(audit);
            } catch (DataIntegrityViolationException e) {
                log.info("Paystack webhook duplicate on save ignored: eventId={}", eventId);
                return true;
            }
        }

        if (parsed.success()) {
            // Scope §4.3: signature → ingest → verify → mark paid
            verifyAndUpdate(checkout);
        } else if (parsed.terminalFailure()) {
            markFailed(checkout, parsed.failureMessage() != null
                    ? parsed.failureMessage()
                    : "Payment declined by Paystack");
        }
        return true;
    }

    /**
     * Server-side verify of one pending checkout (webhook fallback + reconciliation).
     */
    @Transactional
    public void verifyAndUpdate(GatewayCheckout checkout) {
        if (!GatewayCheckoutStatuses.PENDING.equals(checkout.getStatus())) {
            return;
        }
        Map<String, String> creds = resolvePaystackCredentialsForCheckout(checkout);
        if (creds == null
                || !(gatewayRegistry.get(checkout.getGatewayType().name()) instanceof CheckoutPaymentGateway gateway)) {
            markCancelled(checkout, "Gateway configuration no longer available");
            return;
        }
        VerifyTransactionResponse result;
        try {
            result = gateway.verifyTransaction(
                    new VerifyTransactionRequest(checkout.getReference(), creds));
        } catch (Exception e) {
            // Transient provider/network error — leave the checkout PENDING so the
            // reconciler / provider webhook retries later.
            log.error("Paystack verify threw for checkout={}", checkout.getId(), e);
            return;
        }
        checkout.setLastVerifiedAt(Instant.now());
        checkout.setVerifyCount(checkout.getVerifyCount() + 1);
        if (result.completed()) {
            // Confirmation (order paid + Kiosk Pay wallet credit) is all-or-nothing:
            // a credit failure rolls the transaction back so the checkout stays
            // PENDING and the provider webhook / reconciler retries.
            confirmCheckout(
                    checkout,
                    result.providerTransactionId(),
                    result.amount(),
                    result.providerFee());
        } else if (result.failed()) {
            markFailed(checkout, result.failureMessage() != null
                    ? result.failureMessage()
                    : "Payment not completed");
        } else {
            checkoutRepository.save(checkout); // persist lastVerifiedAt / verifyCount
        }
    }

    // ── Reconciliation (scheduler) ───────────────────────────────────

    /**
     * Verify recent PENDING checkouts; abandon anything older than
     * {@code abandonBefore} or that has exhausted {@code maxAttempts}.
     */
    public void reconcileStalePending(Instant createdAfter, Instant abandonBefore, int maxAttempts) {
        var recent = checkoutRepository.findByStatusAndCreatedAtAfterOrderByCreatedAtAsc(
                GatewayCheckoutStatuses.PENDING, createdAfter);
        for (GatewayCheckout checkout : recent) {
            try {
                if (checkout.getVerifyCount() >= maxAttempts) {
                    markCancelled(checkout, "Checkout abandoned — no payment after " + maxAttempts + " verify attempts");
                } else {
                    verifyAndUpdate(checkout);
                }
            } catch (Exception e) {
                log.warn("Paystack reconcile failed for checkout={}: {}", checkout.getId(), e.getMessage());
            }
        }

        var abandoned = checkoutRepository.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                GatewayCheckoutStatuses.PENDING, abandonBefore, PageRequest.of(0, 100));
        for (GatewayCheckout checkout : abandoned) {
            try {
                // One last verify in case payment completed just outside the window
                if (checkout.getVerifyCount() < maxAttempts) {
                    verifyAndUpdate(checkout);
                }
                if (GatewayCheckoutStatuses.PENDING.equals(checkout.getStatus())) {
                    markCancelled(checkout, "Checkout abandoned — older than reconcile window");
                }
            } catch (Exception e) {
                log.warn("Paystack abandon failed for checkout={}: {}", checkout.getId(), e.getMessage());
            }
        }
    }

    public Optional<GatewayCheckout> findLatestForWebOrder(String orderId) {
        return checkoutRepository.findFirstByContextTypeAndContextIdOrderByCreatedAtDesc(
                GatewayCheckoutContextType.WEB_ORDER, orderId);
    }

    /**
     * Admin visibility: recent checkout attempts for one tenant gateway config
     * (used by the Payments Settings UI). The config must belong to the business.
     */
    @Transactional(readOnly = true)
    public List<GatewayCheckoutResponse> listForConfig(String businessId, String configId, int limit) {
        PaymentGatewayConfig cfg = configRepository.findById(configId).orElse(null);
        if (cfg == null || !businessId.equals(cfg.getBusinessId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Gateway config not found");
        }
        int capped = Math.min(Math.max(limit, 1), 50);
        return checkoutRepository
                .findByConfigIdOrderByCreatedAtDesc(configId, PageRequest.of(0, capped))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // ── Confirmation / failure ───────────────────────────────────────

    private void confirmCheckout(
            GatewayCheckout checkout,
            String providerTxnId,
            BigDecimal providerAmount,
            BigDecimal providerFee
    ) {
        if (!GatewayCheckoutStatuses.PENDING.equals(checkout.getStatus())) {
            return;
        }
        if (providerAmount != null
                && providerAmount.subtract(checkout.getAmount()).abs().compareTo(AMOUNT_TOLERANCE) > 0) {
            log.error("Paystack amount mismatch checkout={} expected={} got={} — refusing to confirm",
                    checkout.getId(), checkout.getAmount(), providerAmount);
            markFailed(checkout, "Amount mismatch: expected " + checkout.getAmount()
                    + " got " + providerAmount);
            return;
        }
        checkout.setStatus(GatewayCheckoutStatuses.SUCCESS);
        checkout.setProviderTransactionId(providerTxnId != null ? providerTxnId : checkout.getProviderTransactionId());
        checkout.setConfirmedAt(Instant.now());
        checkout.setFailureReason(null);
        checkoutRepository.save(checkout);

        if (checkout.getContextType() == GatewayCheckoutContextType.WEB_ORDER) {
            confirmWebOrder(checkout, providerFee);
        }
    }

    private void confirmWebOrder(GatewayCheckout checkout, BigDecimal providerFee) {
        if (checkout.getContextId() == null) {
            return;
        }
        WebOrder order = webOrderRepository.findById(checkout.getContextId()).orElse(null);
        if (order == null || !checkout.getBusinessId().equals(order.getBusinessId())) {
            return;
        }
        if (!WebOrderStatuses.PENDING_PAYMENT.equals(order.getStatus())
                && !WebOrderStatuses.PAYMENT_FAILED.equals(order.getStatus())) {
            return;
        }
        order.setStatus(WebOrderStatuses.PAID);
        order.setPaymentCheckoutId(checkout.getReference());
        order.setPaidAt(Instant.now());
        webOrderRepository.save(order);

        if (PlatformKioskPaySettings.PLATFORM_PAYSTACK_CONFIG_ID.equals(checkout.getConfigId())) {
            KioskPayWalletService wallet = kioskPayWalletService.getIfAvailable();
            if (wallet != null) {
                // All-or-nothing: if the wallet credit fails, the whole confirm rolls
                // back (order stays unpaid, checkout stays PENDING) so we never mark
                // an order paid without crediting the merchant wallet. Webhook
                // re-delivery / the checkout reconciler retries.
                wallet.creditPaymentCapture(
                        checkout.getBusinessId(),
                        checkout.getAmount(),
                        checkout.getCurrency(),
                        "kp-capture-" + checkout.getReference(),
                        checkout.getContextType().name(),
                        checkout.getContextId(),
                        checkout.getId(),
                        providerFee);
            }
        }

        try {
            webOrderFulfillmentService.onOrderPaid(order);
            notificationOutboxService.enqueueWebOrderPaid(order);
        } catch (Exception e) {
            log.warn("Failed notifications for paid web order {} (Paystack)", order.getId(), e);
        }
        log.info("Web order marked paid via Paystack: orderId={} ref={}", order.getId(), checkout.getReference());
    }

    private void markFailed(GatewayCheckout checkout, String reason) {
        if (!GatewayCheckoutStatuses.PENDING.equals(checkout.getStatus())) {
            return;
        }
        checkout.setStatus(GatewayCheckoutStatuses.FAILED);
        checkout.setFailureReason(reason);
        checkoutRepository.save(checkout);
        if (checkout.getContextType() == GatewayCheckoutContextType.WEB_ORDER
                && checkout.getContextId() != null) {
            webOrderRepository.findById(checkout.getContextId()).ifPresent(order -> {
                if (WebOrderStatuses.PENDING_PAYMENT.equals(order.getStatus())) {
                    order.setStatus(WebOrderStatuses.PAYMENT_FAILED);
                    webOrderRepository.save(order);
                }
            });
        }
    }

    private void markCancelled(GatewayCheckout checkout, String reason) {
        if (!GatewayCheckoutStatuses.PENDING.equals(checkout.getStatus())) {
            return;
        }
        checkout.setStatus(GatewayCheckoutStatuses.CANCELLED);
        checkout.setFailureReason(reason);
        checkoutRepository.save(checkout);
        // Order stays PENDING_PAYMENT — the customer may retry with another method.
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private PaymentGatewayConfig resolvePaystackConfig(String businessId, String preferredConfigId) {
        PaymentGatewayConfig cfg = null;
        if (preferredConfigId != null && !preferredConfigId.isBlank()) {
            cfg = configRepository.findById(preferredConfigId).orElse(null);
            if (cfg == null
                    || !businessId.equals(cfg.getBusinessId())
                    || cfg.getGatewayType() != GatewayType.PAYSTACK
                    || cfg.getStatus() != GatewayStatus.ACTIVE) {
                cfg = null;
            }
        }
        if (cfg == null) {
            cfg = configRepository.findByBusinessIdAndGatewayTypeAndStatus(
                            businessId, GatewayType.PAYSTACK, GatewayStatus.ACTIVE)
                    .stream()
                    .findFirst()
                    .orElse(null);
        }
        if (cfg == null || !gatewayRegistry.has(GatewayType.PAYSTACK.name())) {
            return null;
        }
        boolean platformEnabled = platformPaymentGatewayService.listEnabled().stream()
                .anyMatch(pg -> pg.getGatewayType() == GatewayType.PAYSTACK);
        return platformEnabled ? cfg : null;
    }

    private Map<String, String> decryptCredentials(PaymentGatewayConfig cfg) {
        try {
            String decrypted = encryptionService.decrypt(cfg.getCredentialsJson());
            @SuppressWarnings("unchecked")
            Map<String, String> creds = objectMapper.readValue(decrypted, Map.class);
            return creds;
        } catch (Exception e) {
            throw new RuntimeException("Failed to read gateway credentials", e);
        }
    }

    /**
     * Resolve Paystack secret credentials for BYO tenant config or platform Kiosk Pay.
     */
    public Map<String, String> resolvePaystackCredentialsForCheckout(GatewayCheckout checkout) {
        if (checkout == null) {
            return null;
        }
        if (PlatformKioskPaySettings.PLATFORM_PAYSTACK_CONFIG_ID.equals(checkout.getConfigId())) {
            PlatformKioskPaySettingsService kioskSettings = platformKioskPaySettingsService.getIfAvailable();
            if (kioskSettings == null) {
                return null;
            }
            return kioskSettings.paystackCredentials().orElse(null);
        }
        PaymentGatewayConfig cfg = configRepository.findById(checkout.getConfigId()).orElse(null);
        if (cfg == null || !checkout.getBusinessId().equals(cfg.getBusinessId())) {
            return null;
        }
        try {
            return decryptCredentials(cfg);
        } catch (Exception e) {
            log.warn("Paystack: cannot decrypt credentials for config={}", cfg.getId());
            return null;
        }
    }

    /**
     * Build Paystack callback URL, preferring the shopper's browser origin when
     * it matches this business (platform subdomain, custom domain, or the
     * configured public frontend base). Unknown origins fall back to
     * {@code app.public.frontend-base-url} (open-redirect safe).
     */
    private String buildCallbackUrl(
            String businessId,
            String businessSlug,
            String returnOrigin,
            String orderId
    ) {
        String base = resolveAllowedReturnOrigin(businessId, businessSlug, returnOrigin);
        return base.replaceAll("/$", "") + "/shop/checkout?order=" + orderId;
    }

    private String resolveAllowedReturnOrigin(String businessId, String businessSlug, String returnOrigin) {
        String fallback = frontendBaseUrl == null || frontendBaseUrl.isBlank()
                ? "http://localhost:3000"
                : frontendBaseUrl.trim();
        if (returnOrigin == null || returnOrigin.isBlank()) {
            return fallback;
        }
        try {
            URI uri = URI.create(returnOrigin.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null || host.isBlank()
                    || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return fallback;
            }
            int port = uri.getPort();
            String candidate = scheme.toLowerCase(Locale.ROOT) + "://" + host.toLowerCase(Locale.ROOT);
            if (port > 0 && port != 80 && port != 443) {
                candidate = candidate + ":" + port;
            }

            if (originsMatch(candidate, fallback)) {
                return candidate;
            }

            Optional<DomainMapping> mapping = domainMappingRepository.findByDomainAndDeletedAtIsNull(host);
            if (mapping.isPresent()
                    && businessId.equals(mapping.get().getBusinessId())
                    && mapping.get().isActive()
                    && mapping.get().getDeletedAt() == null) {
                return candidate;
            }

            if (businessSlug != null && !businessSlug.isBlank()) {
                String slug = businessSlug.trim().toLowerCase(Locale.ROOT);
                for (String platformHost : platformHosts()) {
                    String apex = platformHost.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
                    if (host.equalsIgnoreCase(slug + "." + apex)) {
                        return candidate;
                    }
                }
            }

            log.warn("Paystack callback: rejecting untrusted returnOrigin={} for business={}",
                    returnOrigin, businessId);
            return fallback;
        } catch (Exception e) {
            log.warn("Paystack callback: invalid returnOrigin={}: {}", returnOrigin, e.getMessage());
            return fallback;
        }
    }

    private List<String> platformHosts() {
        if (platformHostsCsv == null || platformHostsCsv.isBlank()) {
            return List.of("kiosk.ke");
        }
        return Arrays.stream(platformHostsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static boolean originsMatch(String a, String b) {
        try {
            URI ua = URI.create(a.trim());
            URI ub = URI.create(b.trim());
            if (ua.getHost() == null || ub.getHost() == null) {
                return false;
            }
            if (!ua.getHost().equalsIgnoreCase(ub.getHost())) {
                return false;
            }
            String sa = ua.getScheme() != null ? ua.getScheme() : "https";
            String sb = ub.getScheme() != null ? ub.getScheme() : "https";
            if (!sa.equalsIgnoreCase(sb)) {
                return false;
            }
            int pa = ua.getPort() > 0 ? ua.getPort() : ("https".equalsIgnoreCase(sa) ? 443 : 80);
            int pb = ub.getPort() > 0 ? ub.getPort() : ("https".equalsIgnoreCase(sb) ? 443 : 80);
            return pa == pb;
        } catch (Exception e) {
            return false;
        }
    }

    private static String buildReference(String configId, String contextId) {
        String configTag = configId != null ? configId.replace("-", "") : "";
        if (configTag.length() > 8) {
            configTag = configTag.substring(0, 8);
        }
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        return "pay_" + configTag + "_" + contextId + "_" + suffix;
    }

    private static String resolveEmail(String requested, WebOrder order) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        if (order.getCustomerEmail() != null && !order.getCustomerEmail().isBlank()) {
            return order.getCustomerEmail().trim();
        }
        // Paystack requires a syntactically valid email; phones-first orders
        // may not have one. Documented placeholder (scope doc open question #2).
        return "orders+" + order.getId() + "@kiosk.ke";
    }

    private static Map<String, String> routingMetadata(String businessId, String configId, String contextId) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("businessId", businessId);
        metadata.put("configId", configId);
        metadata.put("contextType", GatewayCheckoutContextType.WEB_ORDER.name());
        metadata.put("contextId", contextId);
        return metadata;
    }

    private String toJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return null;
        }
    }

    private GatewayCheckoutResponse toResponse(GatewayCheckout checkout) {
        return new GatewayCheckoutResponse(
                checkout.getId(),
                checkout.getGatewayType().name(),
                checkout.getReference(),
                checkout.getContextType(),
                checkout.getContextId(),
                checkout.getAmount(),
                checkout.getCurrency(),
                checkout.getCustomerEmail(),
                checkout.getStatus(),
                checkout.getProviderTransactionId(),
                checkout.getFailureReason(),
                checkout.getCreatedAt(),
                checkout.getConfirmedAt());
    }

    public record CheckoutInitiation(
            boolean accepted,
            String checkoutId,
            String reference,
            String status,
            String authorizationUrl,
            String message
    ) {
        public static CheckoutInitiation accepted(
                String checkoutId, String reference, String status, String authorizationUrl, String message) {
            return new CheckoutInitiation(true, checkoutId, reference, status, authorizationUrl, message);
        }

        public static CheckoutInitiation rejected(String checkoutId, String reference, String message) {
            return new CheckoutInitiation(false, checkoutId, reference, "FAILED", null, message);
        }
    }
}
