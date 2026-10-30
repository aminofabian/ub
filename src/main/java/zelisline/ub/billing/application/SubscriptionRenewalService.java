package zelisline.ub.billing.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.billing.api.dto.SubscriptionBillingDtos;
import zelisline.ub.billing.domain.PlatformSubscriptionPlan;
import zelisline.ub.billing.domain.SubscriptionBillingStatus;
import zelisline.ub.billing.domain.SubscriptionRenewalOrder;
import zelisline.ub.billing.domain.SubscriptionRenewalOrderStatus;
import zelisline.ub.billing.domain.SuspensionReason;
import zelisline.ub.billing.repository.SubscriptionRenewalOrderRepository;
import zelisline.ub.identity.api.dto.AuthBillingGateResponse;
import zelisline.ub.payments.application.GatewayStkPushService;
import zelisline.ub.payments.application.PaymentGatewayStkService;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.StkPushContextType;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * M-Pesa STK subscription renewal (SUBSCRIPTION_BILLING_SCOPE.md §12).
 *
 * <p>The STK prompt runs through {@link PaymentGatewayStkService#initiate}, the same
 * entry tenant credit payments use, so the shop's active till, paybill, or KopoKopo
 * method sends the prompt. This service never picks a provider itself.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionRenewalService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionRenewalService.class);

    private final SubscriptionRenewalOrderRepository orderRepository;
    private final SubscriptionBillingSettingsService settingsService;
    private final SubscriptionBillingService billingService;
    private final SubscriptionPricingService pricingService;
    private final SubscriptionPlanFitService planFitService;
    private final SubscriptionAccessPolicy accessPolicy;
    private final BusinessRepository businessRepository;
    private final PaymentGatewayStkService paymentGatewayStkService;
    private final GatewayStkPushService gatewayStkPushService;

    @Transactional(readOnly = true)
    public SubscriptionBillingDtos.RenewalQuoteResponse renewalQuote(
            String businessId,
            String tierOverride,
            int periodMonths
    ) {
        if (periodMonths < 1 || periodMonths > 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "period must be between 1 and 12 months");
        }
        Business business = requireBusiness(businessId);
        String tierCode = resolveTier(business, tierOverride);
        if (isFreeTier(tierCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Free tier does not require renewal payment");
        }
        PlatformSubscriptionPlan plan = settingsService.requireActivePlan(tierCode);
        assertPackageChangeAllowed(business, plan);
        BigDecimal amount = pricingService.resolveRenewalAmount(plan, periodMonths);
        BigDecimal listPrice = periodMonths == SubscriptionPricingService.ANNUAL_PERIOD_MONTHS
                ? pricingService.annualListPrice(plan)
                : amount;
        BigDecimal savings = periodMonths == SubscriptionPricingService.ANNUAL_PERIOD_MONTHS
                ? pricingService.annualSavings(plan)
                : BigDecimal.ZERO;
        return new SubscriptionBillingDtos.RenewalQuoteResponse(
                plan.getTierCode(),
                plan.getDisplayName(),
                periodMonths,
                amount,
                listPrice,
                savings,
                business.getCurrency(),
                business.getCurrentPeriodEnd(),
                business.getGraceEndsAt());
    }

    @Transactional
    public SubscriptionBillingDtos.RenewSubscriptionResponse initiate(
            String businessId,
            SubscriptionBillingDtos.RenewSubscriptionRequest body
    ) {
        if (!settingsService.isBillingEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Subscription billing is not enabled yet.");
        }
        int periodMonths = body.periodMonths() != null && body.periodMonths() > 0 ? body.periodMonths() : 1;
        SubscriptionBillingDtos.RenewalQuoteResponse quote = renewalQuote(businessId, body.tier(), periodMonths);
        String phone = StkPhoneNormalizer.normalize(body.phone());
        if (phone == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid M-Pesa phone number");
        }
        if (quote.amountKes().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Renewal amount must be positive");
        }
        SubscriptionRenewalOrder order = new SubscriptionRenewalOrder();
        order.setBusinessId(businessId);
        order.setTierCode(quote.tier());
        order.setPeriodMonths(periodMonths);
        order.setAmountKes(quote.amountKes());
        order.setPhoneNumber(phone);
        order.setStatus(SubscriptionRenewalOrderStatus.PENDING);
        SubscriptionRenewalOrder saved = orderRepository.save(order);

        String reference = "sub-" + saved.getId().replace("-", "").substring(0, 12)
                + "-" + UUID.randomUUID().toString().substring(0, 6);
        String description = "Kiosk subscription " + quote.tierDisplayName() + " × " + periodMonths + " mo";

        gatewayStkPushService.cancelPendingForPhone(businessId, phone, "Replaced by subscription renewal");

        // Same entry credit-account payments use: the shop's active STK method.
        PaymentGatewayStkService.StkPushOutcome outcome = paymentGatewayStkService.initiate(
                businessId,
                phone,
                quote.amountKes(),
                reference,
                description);

        if (outcome.accepted() && outcome.checkoutRequestId() != null && outcome.gatewayType() != null) {
            var push = gatewayStkPushService.registerPush(
                    businessId,
                    GatewayType.valueOf(outcome.gatewayType()),
                    outcome.configId(),
                    outcome.checkoutRequestId(),
                    reference,
                    StkPushContextType.SUBSCRIPTION_RENEWAL,
                    saved.getId(),
                    quote.amountKes(),
                    phone);
            saved.setStkPushId(push.getId());
            orderRepository.save(saved);
            return new SubscriptionBillingDtos.RenewSubscriptionResponse(
                    saved.getId(),
                    saved.getStatus().name(),
                    saved.getAmountKes(),
                    phone,
                    "Check your phone to complete M-Pesa payment.");
        }

        saved.setStatus(SubscriptionRenewalOrderStatus.FAILED);
        orderRepository.save(saved);
        return new SubscriptionBillingDtos.RenewSubscriptionResponse(
                saved.getId(),
                saved.getStatus().name(),
                saved.getAmountKes(),
                phone,
                outcome.message() != null ? outcome.message() : "Payment request declined");
    }

    /** STK callback — idempotent via order status + receipt guard. */
    @Transactional
    public void markPaid(String orderId, String checkoutId, String txnId) {
        SubscriptionRenewalOrder order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null) {
            log.warn("Subscription renewal order not found for STK settle orderId={}", orderId);
            return;
        }
        if (order.getStatus() == SubscriptionRenewalOrderStatus.PAID) {
            return;
        }
        if (order.getStatus() != SubscriptionRenewalOrderStatus.PENDING
                && order.getStatus() != SubscriptionRenewalOrderStatus.EXPIRED
                && order.getStatus() != SubscriptionRenewalOrderStatus.FAILED) {
            log.warn("Subscription renewal not settleable — ignoring STK settle orderId={} status={}",
                    orderId, order.getStatus());
            return;
        }
        if (txnId != null && !txnId.isBlank()) {
            orderRepository.findByMpesaReceipt(txnId.trim()).ifPresent(existing -> {
                if (existing.getStatus() == SubscriptionRenewalOrderStatus.PAID) {
                    throw new IllegalStateException(
                            "M-Pesa receipt already applied to another renewal order: " + txnId);
                }
            });
        }
        order.setStatus(SubscriptionRenewalOrderStatus.PAID);
        order.setMpesaReceipt(txnId != null ? txnId.trim() : null);
        order.setPaidAt(Instant.now());
        orderRepository.save(order);
        billingService.activateRenewal(order);
    }

    @Transactional
    public void markFailed(String businessId, String orderId, String reason) {
        SubscriptionRenewalOrder order = orderRepository
                .findByIdAndBusinessIdForUpdate(orderId, businessId)
                .orElse(null);
        if (order == null || order.getStatus() != SubscriptionRenewalOrderStatus.PENDING) {
            return;
        }
        order.setStatus(SubscriptionRenewalOrderStatus.FAILED);
        orderRepository.save(order);
        log.info("Subscription renewal failed business={} orderId={} reason={}", businessId, orderId, reason);
    }

    @Transactional
    public SubscriptionBillingDtos.RenewalOrderStatusResponse status(String businessId, String orderId) {
        SubscriptionRenewalOrder order = orderRepository
                .findByIdAndBusinessIdForUpdate(orderId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Renewal order not found"));
        // No callback ever arrived (phone ignored the prompt, network died, …).
        // Close the loop after 15 minutes so the UI can offer a retry instead
        // of polling a PENDING row forever.
        if (order.getStatus() == SubscriptionRenewalOrderStatus.PENDING
                && order.getCreatedAt() != null
                && order.getCreatedAt().plus(15, ChronoUnit.MINUTES).isBefore(Instant.now())) {
            order.setStatus(SubscriptionRenewalOrderStatus.EXPIRED);
            orderRepository.save(order);
        }
        boolean needsRetry = order.getStatus() == SubscriptionRenewalOrderStatus.FAILED
                || order.getStatus() == SubscriptionRenewalOrderStatus.EXPIRED;
        return new SubscriptionBillingDtos.RenewalOrderStatusResponse(
                order.getId(),
                order.getStatus().name(),
                order.getAmountKes(),
                order.getMpesaReceipt(),
                order.getPaidAt(),
                needsRetry);
    }

    /** Login payload when tenant is billing-suspended — drives renewal wall. */
    @Transactional(readOnly = true)
    public AuthBillingGateResponse authGate(String businessId) {
        if (!accessPolicy.isAccessBlockingEnabled()) {
            return null;
        }
        Business business = businessRepository.findByIdAndDeletedAtIsNull(businessId).orElse(null);
        if (business == null) {
            return null;
        }
        if (business.getSubscriptionBillingStatus() != SubscriptionBillingStatus.SUSPENDED
                || business.getSuspensionReason() != SuspensionReason.BILLING_UNPAID) {
            return null;
        }
        SubscriptionBillingDtos.RenewalQuoteResponse quote = renewalQuote(businessId, null, 1);
        return new AuthBillingGateResponse(
                business.getSubscriptionBillingStatus().name(),
                business.getSuspensionReason().name(),
                quote);
    }

    private Business requireBusiness(String businessId) {
        return businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));
    }

    /**
     * Self-serve packages are a ladder: keep the current tier for a renewal,
     * move up to a bigger one, never down. A higher tier must also cover what
     * the shop already runs (catalog, seats, trailing daily sales).
     */
    private void assertPackageChangeAllowed(Business business, PlatformSubscriptionPlan target) {
        PlatformSubscriptionPlan current = settingsService.planEvenIfInactive(business.getSubscriptionTier());
        if (current == null) {
            return;
        }
        boolean sameTier = current.getTierCode().equalsIgnoreCase(target.getTierCode());
        if (target.getSortOrder() < current.getSortOrder()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Packages only move up. You are on "
                            + current.getDisplayName()
                            + " — choose a higher package or contact support.");
        }
        if (sameTier) {
            // Renewing the current plan is always allowed, even when live usage
            // has outgrown it; the upgrade banner still nudges a bigger tier.
            return;
        }
        SubscriptionPlanFit.Usage usage = planFitService.usage(business.getId());
        if (!planFitService.planCovers(target, usage)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    target.getDisplayName()
                            + " is too small for this shop's catalog, team, or daily sales. Choose a bigger package.");
        }
    }

    private static String resolveTier(Business business, String tierOverride) {
        if (tierOverride != null && !tierOverride.isBlank()) {
            return tierOverride.trim().toLowerCase(Locale.ROOT);
        }
        return business.getSubscriptionTier();
    }

    private static boolean isFreeTier(String tier) {
        return tier != null && "free".equalsIgnoreCase(tier.trim());
    }
}
