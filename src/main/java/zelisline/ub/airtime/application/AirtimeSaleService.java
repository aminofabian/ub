package zelisline.ub.airtime.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.airtime.api.dto.AirtimeOrderResponse;
import zelisline.ub.airtime.api.dto.AirtimeQuoteResponse;
import zelisline.ub.airtime.api.dto.AirtimeStorefrontSummaryResponse;
import zelisline.ub.airtime.domain.AirtimeChannels;
import zelisline.ub.airtime.domain.AirtimeNetworks;
import zelisline.ub.airtime.domain.AirtimeOrder;
import zelisline.ub.airtime.domain.AirtimeOrderStatuses;
import zelisline.ub.airtime.domain.AirtimeTenders;
import zelisline.ub.airtime.domain.PlatformAirtimeSettings;
import zelisline.ub.airtime.infrastructure.InstalipaAirtimeGateway;
import zelisline.ub.airtime.infrastructure.InstalipaAirtimeGateway.AirtimeResult;
import zelisline.ub.airtime.repository.AirtimeOrderRepository;
import zelisline.ub.credits.application.CreditSaleDebtService;
import zelisline.ub.payments.application.KioskPayWalletService;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.payments.domain.KioskPayAccount;
import zelisline.ub.platform.realtime.RealtimeBridge;

/**
 * Sells airtime against a tenant's Kiosk Pay wallet.
 *
 * <p>The lifecycle mirrors Kiosk Pay withdrawals because the risk is identical —
 * we hand money to a third party and only hear the outcome asynchronously. Funds
 * are held before the provider is called, settled when Instalipa confirms
 * delivery, and released on any terminal failure. A scheduled reconciler polls
 * anything still in flight so a lost callback can never strand a merchant's cash.
 */
@Service
@RequiredArgsConstructor
public class AirtimeSaleService {

    private static final Logger log = LoggerFactory.getLogger(AirtimeSaleService.class);

    /** REQUESTED with no provider id — the provider never took it, release now. */
    private static final Duration STALE_REQUESTED = Duration.ofSeconds(45);
    /** SUBMITTED / PENDING without a terminal callback — poll, then expire. */
    private static final Duration STALE_PENDING = Duration.ofMinutes(15);
    /** Storefront orders nobody paid for. */
    private static final Duration STALE_AWAITING_PAYMENT = Duration.ofMinutes(30);

    /** Instalipa wording when the platform float cannot cover the request. */
    private static final List<String> FLOAT_MARKERS = List.of(
            "insufficient", "float", "balance is too low", "not enough");

    /** Never leak platform float or provider ops detail to a tenant. */
    private static final String PUBLIC_PROVIDER_FAILURE =
            "Airtime couldn't be sent right now. The money was returned to your wallet — try again shortly.";

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private final AirtimeOrderRepository orderRepository;
    private final PlatformAirtimeSettingsService platformSettings;
    private final BusinessAirtimeSettingsService businessSettings;
    private final KioskPayWalletService walletService;
    private final CreditSaleDebtService creditSaleDebtService;
    private final InstalipaAirtimeGateway gateway;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${app.airtime.float-pause-minutes:10}")
    private long floatPauseMinutes;

    // ── Quoting ──────────────────────────────────────────────────────

    /** Price a prospective sale without committing anything. */
    @Transactional(readOnly = true)
    public AirtimeQuoteResponse quote(String businessId, String rawPhone, BigDecimal rawAmount, boolean storefront) {
        PlatformAirtimeSettings platform = platformSettings.loadSingleton();
        var availability = businessSettings.availability(businessId, storefront);
        String phone = StkPhoneNormalizer.normalize(rawPhone);
        String currency = platform.getCurrency();

        if (phone == null) {
            return new AirtimeQuoteResponse(false, null, null, rawAmount, null, null, null, currency,
                    "Enter a valid Kenyan mobile number");
        }
        if (!availability.available()) {
            return new AirtimeQuoteResponse(false, phone, AirtimeNetworks.detect(phone), rawAmount,
                    null, null, null, currency, availability.reason());
        }

        BigDecimal amount = whole(rawAmount);
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return new AirtimeQuoteResponse(false, phone, AirtimeNetworks.detect(phone), rawAmount,
                    null, null, null, currency, "Enter an amount");
        }
        String limitProblem = amountProblem(amount, platform, availability.maxAmount(),
                availability.dailyRemaining(), availability.walletBalance());
        if (limitProblem != null) {
            return new AirtimeQuoteResponse(false, phone, AirtimeNetworks.detect(phone), amount,
                    null, null, null, currency, limitProblem);
        }

        BigDecimal commission = commissionFor(amount, platform.getTenantCommissionPercent());
        BigDecimal after = availability.walletBalance().subtract(amount).add(commission);
        return new AirtimeQuoteResponse(true, phone, AirtimeNetworks.detect(phone), amount,
                amount, commission, after, currency, null);
    }

    // ── Selling ──────────────────────────────────────────────────────

    /**
     * Sell airtime immediately from the wallet (till or dashboard). The customer
     * has already handed over cash, so this reserves and dispatches in one go.
     */
    @Transactional
    public AirtimeOrderResponse sell(
            String businessId,
            String branchId,
            String cashierUserId,
            String rawPhone,
            BigDecimal rawAmount,
            String channel,
            String customerId,
            String saleId,
            String tender,
            String idempotencyKey
    ) {
        String idem = idempotencyKey != null && !idempotencyKey.isBlank()
                ? idempotencyKey.trim()
                : UUID.randomUUID().toString();

        var existing = orderRepository.findByBusinessIdAndIdempotencyKey(businessId, idem);
        if (existing.isPresent()) {
            return toResponse(existing.get(), null);
        }

        PlatformAirtimeSettings platform = platformSettings.loadSingleton();
        Map<String, String> credentials = requireReadyProvider(platform);

        String resolvedChannel = AirtimeChannels.isKnown(channel) ? channel : AirtimeChannels.POS;
        String resolvedTender = AirtimeTenders.normalize(tender);
        boolean storefront = AirtimeChannels.STOREFRONT.equals(resolvedChannel);
        if (AirtimeTenders.TAB.equals(resolvedTender)) {
            if (customerId == null || customerId.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Pick a customer to charge this airtime to their tab");
            }
        }

        var availability = businessSettings.availability(businessId, storefront);
        if (!availability.available()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    availability.reason() != null ? availability.reason() : "Airtime is not available");
        }

        String phone = StkPhoneNormalizer.normalize(rawPhone);
        if (phone == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A valid Kenyan mobile number is required (e.g. 0712345678)");
        }
        BigDecimal amount = whole(rawAmount);
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount must be a positive whole number");
        }

        // Lock the wallet so the daily cap and balance check cannot race a
        // concurrent sale from a second till.
        KioskPayAccount account = walletService.getOrCreateForUpdate(businessId);
        BigDecimal usedToday = orderRepository.sumCommittedSince(
                businessId, Instant.now().truncatedTo(ChronoUnit.DAYS));
        BigDecimal remaining = platform.getDailyTenantLimit().subtract(usedToday).max(BigDecimal.ZERO);
        String problem = amountProblem(amount, platform, availability.maxAmount(),
                remaining, account.getAvailableBalance());
        if (problem != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problem);
        }

        if (AirtimeTenders.TAB.equals(resolvedTender)) {
            creditSaleDebtService.assertCanCharge(businessId, customerId, amount);
        }

        AirtimeOrder order = newOrder(
                businessId, branchId, resolvedChannel, phone, amount, platform, idem);
        order.setTender(resolvedTender);
        order.setCashierUserId(cashierUserId);
        order.setCustomerId(customerId);
        order.setPayerPhone(phone);
        order.setSaleId(saleId);
        order.setStatus(AirtimeOrderStatuses.REQUESTED);
        try {
            order = orderRepository.save(order);
        } catch (DataIntegrityViolationException e) {
            return toResponse(orderRepository.findByBusinessIdAndIdempotencyKey(businessId, idem)
                    .orElseThrow(() -> e), null);
        }

        walletService.holdForAirtime(
                account, order.getCost(), order.getCurrency(), order.getId(), holdRef(order));

        if (AirtimeTenders.TAB.equals(resolvedTender)) {
            try {
                creditSaleDebtService.applyDebtForAirtime(
                        businessId, order.getId(), customerId, amount);
            } catch (RuntimeException e) {
                failOrder(order, account, e.getMessage() != null
                        ? e.getMessage() : "Could not charge the customer's tab", true);
                return toResponse(order, account);
            }
        }

        return dispatch(order, account, platform, credentials);
    }

    /**
     * Storefront: record the order the shopper wants, but hold nothing and send
     * nothing until their payment is captured.
     */
    @Transactional
    public AirtimeOrderResponse createAwaitingPayment(
            String businessId,
            String rawPhone,
            BigDecimal rawAmount,
            String customerId,
            String idempotencyKey
    ) {
        return createAwaitingPayment(
                businessId, null, null, AirtimeChannels.STOREFRONT, AirtimeTenders.MPESA,
                rawPhone, rawAmount, customerId, null, idempotencyKey);
    }

    /**
     * Record an unpaid airtime order (storefront shopper or till M-Pesa) and wait
     * for STK before holding the wallet.
     */
    @Transactional
    public AirtimeOrderResponse createAwaitingPayment(
            String businessId,
            String branchId,
            String cashierUserId,
            String channel,
            String tender,
            String rawPhone,
            BigDecimal rawAmount,
            String customerId,
            String payerPhone,
            String idempotencyKey
    ) {
        String idem = idempotencyKey != null && !idempotencyKey.isBlank()
                ? idempotencyKey.trim()
                : UUID.randomUUID().toString();
        var existing = orderRepository.findByBusinessIdAndIdempotencyKey(businessId, idem);
        if (existing.isPresent()) {
            return toResponse(existing.get(), null);
        }

        PlatformAirtimeSettings platform = platformSettings.loadSingleton();
        requireReadyProvider(platform);

        String resolvedChannel = AirtimeChannels.isKnown(channel) ? channel : AirtimeChannels.STOREFRONT;
        boolean storefront = AirtimeChannels.STOREFRONT.equals(resolvedChannel);
        var availability = businessSettings.availability(businessId, storefront);
        if (!availability.available()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    availability.reason() != null ? availability.reason() : "Airtime is not available");
        }

        String phone = StkPhoneNormalizer.normalize(rawPhone);
        if (phone == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A valid Kenyan mobile number is required (e.g. 0712345678)");
        }
        BigDecimal amount = whole(rawAmount);
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount must be a positive whole number");
        }
        String problem = amountProblem(amount, platform, availability.maxAmount(),
                availability.dailyRemaining(), availability.walletBalance());
        if (problem != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problem);
        }

        AirtimeOrder order = newOrder(
                businessId, branchId, resolvedChannel, phone, amount, platform, idem);
        order.setTender(AirtimeTenders.normalize(tender));
        order.setCashierUserId(cashierUserId);
        order.setCustomerId(customerId);
        String payer = StkPhoneNormalizer.normalize(
                payerPhone != null && !payerPhone.isBlank() ? payerPhone : rawPhone);
        order.setPayerPhone(payer != null ? payer : phone);
        order.setStatus(AirtimeOrderStatuses.AWAITING_PAYMENT);
        try {
            order = orderRepository.save(order);
        } catch (DataIntegrityViolationException e) {
            return toResponse(orderRepository.findByBusinessIdAndIdempotencyKey(businessId, idem)
                    .orElseThrow(() -> e), null);
        }
        return toResponse(order, null);
    }

    /**
     * The shopper's money landed, so the airtime can now be reserved and sent.
     * Their payment settles wherever the tenant's storefront payments normally go;
     * the airtime itself is always funded by the tenant's wallet float.
     */
    @Transactional
    public void markPaidAndDispatch(String orderId) {
        AirtimeOrder order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("Airtime order {} not found for payment dispatch", orderId);
            return;
        }
        if (!AirtimeOrderStatuses.AWAITING_PAYMENT.equals(order.getStatus())) {
            return;
        }
        order.setPaidAt(Instant.now());

        PlatformAirtimeSettings platform = platformSettings.loadSingleton();
        Map<String, String> credentials = platformSettings.credentials().orElse(null);
        if (credentials == null) {
            failOrder(order, null, "Airtime provider is not configured", false);
            return;
        }

        KioskPayAccount account = walletService.getOrCreateForUpdate(order.getBusinessId());
        if (account.getAvailableBalance().compareTo(order.getCost()) < 0) {
            // The capture should have covered this; if it somehow did not, fail the
            // airtime rather than overdraw — the shopper's payment is refundable.
            failOrder(order, null, "Wallet could not cover the airtime after payment", false);
            return;
        }

        order.setStatus(AirtimeOrderStatuses.REQUESTED);
        orderRepository.save(order);
        walletService.holdForAirtime(
                account, order.getCost(), order.getCurrency(), order.getId(), holdRef(order));
        dispatch(order, account, platform, credentials);
    }

    /** The shopper's payment failed or was abandoned. */
    @Transactional
    public void cancelUnpaid(String orderId, String reason) {
        AirtimeOrder order = orderRepository.findById(orderId).orElse(null);
        if (order == null || !AirtimeOrderStatuses.AWAITING_PAYMENT.equals(order.getStatus())) {
            return;
        }
        order.setStatus(AirtimeOrderStatuses.FAILED);
        order.setFailureReason(truncate(reason != null ? reason : "Payment was not completed", 500));
        order.setCompletedAt(Instant.now());
        orderRepository.save(order);
        publish(order, null);
    }

    /** Hand the held order to Instalipa. Never throws — a throw would undo the hold. */
    private AirtimeOrderResponse dispatch(
            AirtimeOrder order,
            KioskPayAccount account,
            PlatformAirtimeSettings platform,
            Map<String, String> credentials
    ) {
        AirtimeResult result = gateway.sendAirtime(
                credentials,
                platform.getBaseUrl(),
                order.getPhoneNumber(),
                order.getAmount(),
                order.getReference(),
                order.getIdempotencyKey());

        if (result.floatBalance() != null) {
            platformSettings.recordFloatBalance(result.floatBalance());
        }

        if (!result.accepted()) {
            noteProviderFailure(order, result.message());
            failOrder(order, account, result.message(), true);
            return toResponse(order, account);
        }

        order.setProviderTransactionId(result.transactionId());
        order.setProviderStatus(result.providerStatus());
        order.setProviderDetails(truncate(result.details(), 255));
        order.setProviderDiscount(result.discount());
        order.setProviderBalance(result.floatBalance());
        order.setSubmittedAt(Instant.now());

        if (result.success()) {
            settleOrder(order, account, result.receipt());
            return toResponse(order, account);
        }
        if (result.terminalFailure()) {
            noteProviderFailure(order, result.message());
            failOrder(order, account, result.message(), true);
            return toResponse(order, account);
        }

        order.setStatus(AirtimeOrderStatuses.SUBMITTED);
        orderRepository.save(order);
        publish(order, account);
        log.info("Airtime submitted: order={} txn={} business={} amount={}",
                order.getId(), result.transactionId(), order.getBusinessId(), order.getAmount());
        return toResponse(order, account);
    }

    // ── Provider callbacks and reconciliation ────────────────────────

    /**
     * Apply an Instalipa callback. Returns false when the transaction is unknown so
     * the webhook controller can still answer 200 without pretending it matched.
     */
    @Transactional
    public boolean applyProviderUpdate(AirtimeResult result) {
        if (result == null) {
            return false;
        }
        AirtimeOrder order = null;
        if (result.transactionId() != null && !result.transactionId().isBlank()) {
            order = orderRepository.findByProviderTransactionId(result.transactionId()).orElse(null);
        }
        if (order == null && result.reference() != null && !result.reference().isBlank()) {
            order = orderRepository.findByReference(result.reference().trim()).orElse(null);
        }
        if (order == null) {
            return false;
        }
        if (order.isTerminal()) {
            return true;
        }
        if (result.floatBalance() != null) {
            platformSettings.recordFloatBalance(result.floatBalance());
        }

        order.setProviderStatus(result.providerStatus());
        order.setProviderDetails(truncate(result.details(), 255));
        if (result.discount() != null) {
            order.setProviderDiscount(result.discount());
        }
        if (result.floatBalance() != null) {
            order.setProviderBalance(result.floatBalance());
        }
        if (result.transactionId() != null && order.getProviderTransactionId() == null) {
            order.setProviderTransactionId(result.transactionId());
        }

        KioskPayAccount account = walletService.getOrCreate(order.getBusinessId());
        if (result.success()) {
            settleOrder(order, account, result.receipt());
            return true;
        }
        if (result.terminalFailure()) {
            noteProviderFailure(order, result.message());
            failOrder(order, account, result.message(), true);
            return true;
        }
        order.setStatus(AirtimeOrderStatuses.PENDING);
        orderRepository.save(order);
        publish(order, account);
        return true;
    }

    /**
     * Sweep orders the provider still owes us an answer on, so a missed callback
     * cannot leave a merchant's money held indefinitely.
     */
    @Transactional
    public int reconcileAllInFlight() {
        PlatformAirtimeSettings platform = platformSettings.loadSingleton();
        Map<String, String> credentials = platformSettings.credentials().orElse(null);
        Instant now = Instant.now();
        int changed = 0;

        for (AirtimeOrder order : orderRepository.findByStatusInOrderByCreatedAtAsc(List.of(
                AirtimeOrderStatuses.AWAITING_PAYMENT))) {
            Instant started = order.getRequestedAt() != null ? order.getRequestedAt() : order.getCreatedAt();
            if (started != null && started.isBefore(now.minus(STALE_AWAITING_PAYMENT))) {
                cancelUnpaid(order.getId(), "Payment was not completed in time");
                changed++;
            }
        }

        for (AirtimeOrder order : orderRepository.findByStatusInOrderByCreatedAtAsc(List.of(
                AirtimeOrderStatuses.REQUESTED,
                AirtimeOrderStatuses.SUBMITTED,
                AirtimeOrderStatuses.PENDING))) {
            KioskPayAccount account = walletService.getOrCreate(order.getBusinessId());

            if (AirtimeOrderStatuses.REQUESTED.equals(order.getStatus())) {
                boolean neverAccepted = order.getProviderTransactionId() == null
                        || order.getProviderTransactionId().isBlank();
                Instant started = order.getRequestedAt() != null
                        ? order.getRequestedAt()
                        : order.getCreatedAt();
                if (neverAccepted && started != null && started.isBefore(now.minus(STALE_REQUESTED))) {
                    failOrder(order, account,
                            "Airtime was never accepted by the provider — funds released", true);
                    changed++;
                }
                continue;
            }

            String txn = order.getProviderTransactionId();
            if (txn != null && !txn.isBlank() && credentials != null) {
                try {
                    AirtimeResult status = gateway.queryStatus(credentials, platform.getBaseUrl(), txn);
                    if (status.floatBalance() != null) {
                        platformSettings.recordFloatBalance(status.floatBalance());
                    }
                    if (status.success()) {
                        settleOrder(order, account, status.receipt());
                        changed++;
                        continue;
                    }
                    if (status.accepted() && status.terminalFailure()) {
                        noteProviderFailure(order, status.message());
                        failOrder(order, account, status.message(), true);
                        changed++;
                        continue;
                    }
                } catch (Exception e) {
                    log.warn("Airtime status poll failed order={}: {}", order.getId(), e.getMessage());
                }
            }

            Instant submitted = order.getSubmittedAt() != null
                    ? order.getSubmittedAt()
                    : order.getRequestedAt();
            if (submitted != null && submitted.isBefore(now.minus(STALE_PENDING))) {
                failOrder(order, account,
                        "Airtime timed out waiting for the network — funds released", true);
                changed++;
            }
        }
        return changed;
    }

    // ── Reads ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AirtimeOrderResponse> list(String businessId, int limit) {
        return list(businessId, limit, null);
    }

    @Transactional(readOnly = true)
    public List<AirtimeOrderResponse> list(String businessId, int limit, String channel) {
        int capped = Math.min(Math.max(limit, 1), 200);
        var page = PageRequest.of(0, capped);
        List<AirtimeOrder> rows;
        if (channel != null && !channel.isBlank()) {
            String normalized = channel.trim().toUpperCase();
            if (!AirtimeChannels.isKnown(normalized)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown airtime channel");
            }
            rows = orderRepository.findByBusinessIdAndChannelOrderByCreatedAtDesc(
                    businessId, normalized, page);
        } else {
            rows = orderRepository.findByBusinessIdOrderByCreatedAtDesc(businessId, page);
        }
        return rows.stream().map(o -> toResponse(o, null)).toList();
    }

    /** All-time and Nairobi-today totals for storefront airtime. */
    @Transactional(readOnly = true)
    public AirtimeStorefrontSummaryResponse storefrontSummary(String businessId) {
        PlatformAirtimeSettings platform = platformSettings.loadSingleton();
        Instant todayStart = LocalDate.now(NAIROBI).atStartOfDay(NAIROBI).toInstant();
        String channel = AirtimeChannels.STOREFRONT;
        return new AirtimeStorefrontSummaryResponse(
                platform.getCurrency(),
                platform.getTenantCommissionPercent(),
                orderRepository.countByBusinessIdAndChannelAndStatus(
                        businessId, channel, AirtimeOrderStatuses.SUCCESS),
                zeroIfNull(orderRepository.sumSuccessAmountByChannel(businessId, channel)),
                zeroIfNull(orderRepository.sumSuccessCommissionByChannel(businessId, channel)),
                orderRepository.countByBusinessIdAndChannelAndStatusAndCompletedAtGreaterThanEqual(
                        businessId, channel, AirtimeOrderStatuses.SUCCESS, todayStart),
                zeroIfNull(orderRepository.sumSuccessAmountByChannelSince(
                        businessId, channel, todayStart)),
                zeroIfNull(orderRepository.sumSuccessCommissionByChannelSince(
                        businessId, channel, todayStart)),
                orderRepository.countByBusinessIdAndChannelAndStatus(
                        businessId, channel, AirtimeOrderStatuses.AWAITING_PAYMENT),
                orderRepository.countByBusinessIdAndChannelAndStatusIn(
                        businessId, channel, List.of(
                                AirtimeOrderStatuses.REQUESTED,
                                AirtimeOrderStatuses.SUBMITTED,
                                AirtimeOrderStatuses.PENDING)),
                orderRepository.countByBusinessIdAndChannelAndStatus(
                        businessId, channel, AirtimeOrderStatuses.FAILED));
    }

    @Transactional(readOnly = true)
    public AirtimeOrderResponse get(String businessId, String orderId) {
        AirtimeOrder order = orderRepository.findByIdAndBusinessId(orderId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Airtime order not found"));
        return toResponse(order, null);
    }

    @Transactional(readOnly = true)
    public List<AirtimeOrderResponse> listForSuperAdmin(int limit) {
        int capped = Math.min(Math.max(limit, 1), 200);
        return orderRepository
                .findAll(PageRequest.of(0, capped, org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "createdAt")))
                .stream()
                .map(this::toOpsResponse)
                .toList();
    }

    /** Re-query a single order on demand (super-admin ops button). */
    @Transactional
    public AirtimeOrderResponse requeryForSuperAdmin(String orderId) {
        AirtimeOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Airtime order not found"));
        if (order.isTerminal() || order.getProviderTransactionId() == null) {
            return toOpsResponse(order);
        }
        PlatformAirtimeSettings platform = platformSettings.loadSingleton();
        Map<String, String> credentials = platformSettings.credentials()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Instalipa credentials are not configured"));
        AirtimeResult status = gateway.queryStatus(
                credentials, platform.getBaseUrl(), order.getProviderTransactionId());
        applyProviderUpdate(status);
        return toOpsResponse(orderRepository.findById(orderId).orElse(order));
    }

    // ── Internals ────────────────────────────────────────────────────

    private AirtimeOrder newOrder(
            String businessId,
            String branchId,
            String channel,
            String phone,
            BigDecimal amount,
            PlatformAirtimeSettings platform,
            String idempotencyKey
    ) {
        AirtimeOrder order = new AirtimeOrder();
        order.setId(UUID.randomUUID().toString());
        order.setBusinessId(businessId);
        order.setBranchId(branchId);
        order.setChannel(channel);
        order.setTender(AirtimeTenders.CASH);
        order.setPhoneNumber(phone);
        order.setNetwork(AirtimeNetworks.detect(phone));
        order.setAmount(amount);
        order.setCost(amount);
        order.setCommissionPercent(platform.getTenantCommissionPercent());
        order.setCommission(commissionFor(amount, platform.getTenantCommissionPercent()));
        order.setCurrency(platform.getCurrency());
        order.setReference("air-" + order.getId());
        order.setIdempotencyKey(idempotencyKey);
        order.setRequestedAt(Instant.now());
        return order;
    }

    private void settleOrder(AirtimeOrder order, KioskPayAccount account, String receipt) {
        KioskPayAccount wallet = account != null ? account : walletService.getOrCreate(order.getBusinessId());
        walletService.settleAirtime(
                wallet,
                order.getCost(),
                order.getCommission(),
                order.getCurrency(),
                order.getId(),
                settleRef(order));
        order.setStatus(AirtimeOrderStatuses.SUCCESS);
        order.setReceipt(truncate(receipt, 64));
        order.setFailureReason(null);
        order.setCompletedAt(Instant.now());
        orderRepository.save(order);
        publish(order, wallet);
        log.info("Airtime delivered: order={} business={} amount={} commission={}",
                order.getId(), order.getBusinessId(), order.getAmount(), order.getCommission());
    }

    /**
     * Mark the order failed, releasing the wallet hold when one was taken. Stores
     * the raw provider text for ops; tenant responses sanitize it on read.
     */
    private void failOrder(AirtimeOrder order, KioskPayAccount account, String reason, boolean holdTaken) {
        if (order.isTerminal()) {
            return;
        }
        if (holdTaken && AirtimeOrderStatuses.isInFlight(order.getStatus())) {
            KioskPayAccount wallet = account != null
                    ? account
                    : walletService.getOrCreate(order.getBusinessId());
            try {
                walletService.releaseAirtimeHold(
                        wallet, order.getCost(), order.getCurrency(), order.getId(), releaseRef(order));
            } catch (Exception e) {
                log.error("Airtime hold release failed for order={}", order.getId(), e);
            }
        }
        if (AirtimeTenders.TAB.equals(order.getTender())
                && order.getCustomerId() != null
                && !order.getCustomerId().isBlank()) {
            try {
                creditSaleDebtService.reverseDebtForAirtime(
                        order.getBusinessId(), order.getId(), order.getCustomerId());
            } catch (Exception e) {
                log.error("Airtime tab reverse failed for order={}", order.getId(), e);
            }
        }
        order.setStatus(AirtimeOrderStatuses.FAILED);
        order.setFailureReason(truncate(reason != null ? reason : "Airtime failed", 500));
        order.setCompletedAt(Instant.now());
        orderRepository.save(order);
        publish(order, account);
        log.info("Airtime failed: order={} business={} amount={} phone={} reason={}",
                order.getId(), order.getBusinessId(), order.getAmount(), order.getPhoneNumber(), reason);
    }

    private Map<String, String> requireReadyProvider(PlatformAirtimeSettings platform) {
        if (!platform.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Airtime is not enabled on this platform");
        }
        if (platform.isFloatConstrained(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Airtime is temporarily unavailable. Try again shortly — your wallet was not touched.");
        }
        return platformSettings.credentials().orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "The airtime provider is not configured yet"));
    }

    /**
     * Returns a tenant-readable problem with the amount, or null when it is sellable.
     */
    private String amountProblem(
            BigDecimal amount,
            PlatformAirtimeSettings platform,
            BigDecimal maxAllowed,
            BigDecimal dailyRemaining,
            BigDecimal walletBalance
    ) {
        if (amount.compareTo(platform.getMinAmount()) < 0) {
            return "Minimum airtime is " + platform.getCurrency() + " " + strip(platform.getMinAmount());
        }
        if (maxAllowed != null && amount.compareTo(maxAllowed) > 0) {
            return "Maximum airtime is " + platform.getCurrency() + " " + strip(maxAllowed);
        }
        if (dailyRemaining != null && amount.compareTo(dailyRemaining) > 0) {
            return "That would pass today's airtime limit — " + platform.getCurrency() + " "
                    + strip(dailyRemaining) + " left";
        }
        if (walletBalance == null || amount.compareTo(walletBalance) > 0) {
            return "Not enough Kiosk Pay balance — top up to sell this much airtime";
        }
        return null;
    }

    private void noteProviderFailure(AirtimeOrder order, String raw) {
        if (looksLikeFloatProblem(raw)) {
            platformSettings.markFloatConstrained(Duration.ofMinutes(floatPauseMinutes));
            log.warn("Airtime FLOAT — paused {} min | order={} business={} amount={} provider=\"{}\"",
                    floatPauseMinutes, order.getId(), order.getBusinessId(), order.getAmount(), raw);
            return;
        }
        log.warn("Airtime provider rejected | order={} business={} amount={} phone={} provider=\"{}\"",
                order.getId(), order.getBusinessId(), order.getAmount(), order.getPhoneNumber(), raw);
    }

    private static boolean looksLikeFloatProblem(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        return FLOAT_MARKERS.stream().anyMatch(lower::contains);
    }

    /** Platform float and provider plumbing are ours to worry about, not the tenant's. */
    static String publicFailureReason(String stored) {
        if (stored == null || stored.isBlank()) {
            return stored;
        }
        if (looksLikeFloatProblem(stored)) {
            return PUBLIC_PROVIDER_FAILURE;
        }
        String lower = stored.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("instalipa") || lower.contains("http ") || lower.contains("consumer key")) {
            return PUBLIC_PROVIDER_FAILURE;
        }
        return stored;
    }

    private void publish(AirtimeOrder order, KioskPayAccount account) {
        BigDecimal balance = account != null ? account.getAvailableBalance() : null;
        eventPublisher.publishEvent(new RealtimeBridge.AirtimeOrderUpdatedEvent(
                order.getBusinessId(),
                order.getId(),
                order.getStatus(),
                order.getPhoneNumber(),
                order.getAmount(),
                order.getCommission(),
                order.getCurrency(),
                order.getReceipt(),
                publicFailureReason(order.getFailureReason()),
                balance));
    }

    private static BigDecimal commissionFor(BigDecimal amount, BigDecimal percent) {
        if (amount == null || percent == null || percent.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return amount.multiply(percent)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }

    /** Instalipa only accepts whole shillings. */
    private static BigDecimal whole(BigDecimal raw) {
        if (raw == null) {
            return null;
        }
        return raw.setScale(0, RoundingMode.DOWN);
    }

    private static String strip(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal zeroIfNull(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String holdRef(AirtimeOrder order) {
        return "air-hold-" + order.getId();
    }

    private static String settleRef(AirtimeOrder order) {
        return "air-settle-" + order.getId();
    }

    private static String releaseRef(AirtimeOrder order) {
        return "air-release-" + order.getId();
    }

    private AirtimeOrderResponse toResponse(AirtimeOrder order, KioskPayAccount account) {
        return new AirtimeOrderResponse(
                order.getId(),
                order.getBusinessId(),
                order.getChannel(),
                order.getTender(),
                order.getPhoneNumber(),
                order.getPayerPhone(),
                order.getNetwork(),
                order.getAmount(),
                order.getCost(),
                order.getCommission(),
                order.getCurrency(),
                order.getStatus(),
                order.getReference(),
                order.getProviderTransactionId(),
                order.getProviderStatus(),
                order.getReceipt(),
                publicFailureReason(order.getFailureReason()),
                account != null ? account.getAvailableBalance() : null,
                order.getRequestedAt(),
                order.getCompletedAt());
    }

    /** Super-admin sees the untouched provider failure text. */
    private AirtimeOrderResponse toOpsResponse(AirtimeOrder order) {
        return new AirtimeOrderResponse(
                order.getId(),
                order.getBusinessId(),
                order.getChannel(),
                order.getTender(),
                order.getPhoneNumber(),
                order.getPayerPhone(),
                order.getNetwork(),
                order.getAmount(),
                order.getCost(),
                order.getCommission(),
                order.getCurrency(),
                order.getStatus(),
                order.getReference(),
                order.getProviderTransactionId(),
                order.getProviderStatus(),
                order.getReceipt(),
                order.getFailureReason(),
                null,
                order.getRequestedAt(),
                order.getCompletedAt());
    }
}
