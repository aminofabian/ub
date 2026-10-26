package zelisline.ub.domainoffer.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.IDN;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.domainoffer.api.dto.DomainOfferDtos;
import zelisline.ub.domainoffer.api.dto.PublicDomainOfferDtos;
import zelisline.ub.domainoffer.domain.DomainOfferCampaign;
import zelisline.ub.domainoffer.domain.DomainOfferCampaignStatus;
import zelisline.ub.domainoffer.domain.DomainOfferDomainMode;
import zelisline.ub.domainoffer.domain.DomainOfferOrder;
import zelisline.ub.domainoffer.domain.DomainOfferStatus;
import zelisline.ub.domainoffer.domain.DomainOfferThemeDirection;
import zelisline.ub.domainoffer.repository.DomainOfferCampaignRepository;
import zelisline.ub.domainoffer.repository.DomainOfferOrderRepository;
import zelisline.ub.payments.application.GatewayStkPushService;
import zelisline.ub.payments.application.PaymentGatewayStkService;
import zelisline.ub.payments.application.PlatformStkRailService;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.payments.domain.GatewayStkPushStatuses;
import zelisline.ub.payments.domain.StkPushContextType;
import zelisline.ub.tenancy.api.dto.CreateDomainOrderRequest;
import zelisline.ub.tenancy.api.dto.DomainResponse;
import zelisline.ub.tenancy.api.dto.DomainOrderResponse;
import zelisline.ub.tenancy.api.dto.StorefrontTemplateIds;
import zelisline.ub.tenancy.application.DomainPurchaseService;
import zelisline.ub.tenancy.application.ReservedHostnameGuard;
import zelisline.ub.tenancy.application.TenancyService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.DomainMapping;
import zelisline.ub.tenancy.domain.DomainStatus;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * The "own your address" offer: a KES 1,000 custom-domain + theme setup,
 * collected on the platform M-Pesa Express rail (the same rail as SMS top-ups
 * and package renewals) and fulfilled by Super Admin within 24 hours.
 */
@Service
@RequiredArgsConstructor
public class DomainOfferService {

    private static final Logger log = LoggerFactory.getLogger(DomainOfferService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String REFERENCE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int MAX_ORDERS = 200;
    /** The super-admin test email opens this token; it is demo-only and never payable. */
    public static final String PREVIEW_TOKEN = "preview-token";

    private static final List<PublicDomainOfferDtos.ThemeOption> CURATED_THEMES = List.of(
            new PublicDomainOfferDtos.ThemeOption("mart", "Market day", "Produce, price tags, and a busy counter."),
            new PublicDomainOfferDtos.ThemeOption(
                    "boutique-shelf", "Boutique shelf", "Quiet, considered, and a little premium."),
            new PublicDomainOfferDtos.ThemeOption(
                    "butcher-board", "Butcher board", "Chalk, cuts, and a serious counter."),
            new PublicDomainOfferDtos.ThemeOption(
                    "comilmart", "Everyday minimart", "Familiar, fast, and easy to scan."));

    private final DomainOfferCampaignRepository campaignRepository;
    private final DomainOfferOrderRepository orderRepository;
    private final DomainOfferAudienceService audienceService;
    private final DomainOfferMessagingService messagingService;
    private final BusinessRepository businessRepository;
    private final DomainMappingRepository domainMappingRepository;
    private final ReservedHostnameGuard reservedHostnameGuard;
    private final TenancyService tenancyService;
    private final DomainPurchaseService domainPurchaseService;
    private final PlatformStkRailService platformStkRailService;
    private final PaymentGatewayStkService paymentGatewayStkService;
    private final GatewayStkPushService gatewayStkPushService;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    // ── Super Admin campaign ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DomainOfferDtos.DomainOfferCampaignResponse getCampaign() {
        DomainOfferCampaign campaign = campaignRepository.findFirstByOrderByCreatedAtDesc().orElse(null);
        DomainOfferDtos.DomainOfferStats stats = stats(campaign, eligibleFor(campaign));
        return toCampaignResponse(campaign, stats);
    }

    @Transactional
    public DomainOfferDtos.DomainOfferCampaignResponse startCampaign(
            DomainOfferDtos.StartCampaignRequest request,
            String superAdminId
    ) {
        DomainOfferCampaign latest = campaignRepository.findFirstByOrderByCreatedAtDesc().orElse(null);
        if (latest != null && latest.getStatus() == DomainOfferCampaignStatus.RUNNING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This offer is already running");
        }
        if (latest != null && latest.getStatus() == DomainOfferCampaignStatus.PAUSED) {
            latest.setStatus(DomainOfferCampaignStatus.RUNNING);
            latest.setPausedAt(null);
            campaignRepository.save(latest);
            audit(superAdminId, AuditEventTypes.DOMAIN_OFFER_CAMPAIGN_STARTED, latest.getId(),
                    "resumed", "Resumed the domain + theme offer");
            return getCampaignResponse(latest);
        }

        List<DomainOfferAudienceService.Recipient> audience = audienceService.resolve();
        if (audience.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "No eligible shops. Every live shop either has a custom domain or was offered this in the last 60 days.");
        }

        DomainOfferCampaign campaign = new DomainOfferCampaign();
        campaign.setName(blankToDefault(request == null ? null : request.name(), "Own your address — KES 1,000"));
        campaign.setStatus(DomainOfferCampaignStatus.RUNNING);
        campaign.setRecipientsTargeted(audience.size());
        campaign.setCreatedBySuperAdminId(superAdminId);
        campaign.setStartedAt(Instant.now());
        DomainOfferCampaign saved = campaignRepository.save(campaign);

        Instant now = Instant.now();
        int index = 0;
        for (DomainOfferAudienceService.Recipient recipient : audience) {
            DomainOfferOrder order = new DomainOfferOrder();
            order.setCampaignId(saved.getId());
            order.setBusinessId(recipient.business().getId());
            order.setReference(reference());
            order.setPayToken(token());
            order.setStatus(DomainOfferStatus.INVITED);
            order.setDomainMode(DomainOfferDomainMode.OWNED);
            order.setSuggestedDomain(recipient.suggestedDomain());
            order.setThemeKey("mart");
            order.setThemeDirection(DomainOfferThemeDirection.DESIGNER_CHOICE.name());
            order.setContactName(recipient.contactName());
            order.setContactEmail(recipient.contactEmail());
            order.setContactPhone(null);
            order.setAmountCents(saved.getPriceCents());
            order.setCurrency(saved.getCurrency());
            order.setRegistrationStatus(DomainOfferOrder.REGISTRATION_NOT_NEEDED);
            // Slight stagger so a large audience does not all send in one batch.
            order.setNextTouchAt(now.plusSeconds(Math.min(index, 600) * 2L));
            orderRepository.save(order);
            index++;
        }

        audit(superAdminId, AuditEventTypes.DOMAIN_OFFER_CAMPAIGN_STARTED, saved.getId(),
                "started", "Enrolled " + audience.size() + " shops in the domain + theme offer");
        return getCampaignResponse(saved);
    }

    @Transactional
    public DomainOfferDtos.DomainOfferCampaignResponse pauseCampaign(String superAdminId) {
        DomainOfferCampaign campaign = requireLatestCampaign();
        if (campaign.getStatus() != DomainOfferCampaignStatus.RUNNING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a running offer can be paused");
        }
        campaign.setStatus(DomainOfferCampaignStatus.PAUSED);
        campaign.setPausedAt(Instant.now());
        campaignRepository.save(campaign);
        audit(superAdminId, AuditEventTypes.DOMAIN_OFFER_CAMPAIGN_STARTED, campaign.getId(),
                "paused", "Paused the domain + theme offer");
        return getCampaignResponse(campaign);
    }

    @Transactional(readOnly = true)
    public List<DomainOfferDtos.DomainOfferTouch> touches() {
        return DomainOfferCopy.touches().stream()
                .map(t -> new DomainOfferDtos.DomainOfferTouch(
                        t.index(), t.dayOffset(), t.label(), t.subject(), t.preview(),
                        t.bodyMarkdown(), t.cta(), t.inAppTitle(), t.inAppBody()))
                .toList();
    }

    @Transactional
    public DomainOfferDtos.DomainOfferOrderResponse assign(String orderId, String assignedTo) {
        DomainOfferOrder order = requireOrder(orderId);
        requirePaidForFulfilment(order);
        order.setAssignedTo(blankToNull(assignedTo));
        if (order.getStatus() == DomainOfferStatus.PAID) {
            order.setStatus(DomainOfferStatus.IN_PROGRESS);
            if (order.getStartedAt() == null) {
                order.setStartedAt(Instant.now());
            }
        }
        return toOrderResponse(orderRepository.save(order), businessFor(order.getBusinessId()));
    }

    @Transactional
    public DomainOfferDtos.DomainOfferOrderResponse connectDomain(String orderId) {
        DomainOfferOrder order = requireOrder(orderId);
        requirePaidForFulfilment(order);
        String domain = normalizeDomain(order.effectiveDomain());
        connectDomainInternal(order, domain);
        return toOrderResponse(orderRepository.save(order), businessFor(order.getBusinessId()));
    }

    @Transactional
    public DomainOfferDtos.DomainOfferOrderResponse deliver(
            String orderId,
            DomainOfferDtos.DeliverOrderRequest request
    ) {
        DomainOfferOrder order = requireOrder(orderId);
        requirePaidForFulfilment(order);
        if (order.getStatus() == DomainOfferStatus.DELIVERED) {
            return toOrderResponse(order, businessFor(order.getBusinessId()));
        }
        String domain = normalizeDomain(request == null ? null : request.domain());
        order.setRequestedDomain(domain);
        order.setDeliveredDomain(domain);
        if (Boolean.TRUE.equals(request == null ? null : request.connectDomain())) {
            connectDomainInternal(order, domain);
        }
        order.setStatus(DomainOfferStatus.DELIVERED);
        order.setDeliveredAt(Instant.now());
        if (order.getStartedAt() == null) {
            order.setStartedAt(Instant.now());
        }
        if (request != null && request.notes() != null && !request.notes().isBlank()) {
            order.setFulfilmentNotes(request.notes().trim());
        }
        order.setRegistrationStatus(registrationStatusForDelivered(order));
        order.setNextTouchAt(null);
        order.setLastError(null);
        DomainOfferOrder saved = orderRepository.save(order);
        Business business = businessFor(saved.getBusinessId());
        messagingService.sendDelivered(saved, business);
        audit(null, AuditEventTypes.DOMAIN_OFFER_DELIVERED, saved.getId(),
                "delivered", "Delivered " + domain + " for business " + saved.getBusinessId());
        return toOrderResponse(saved, business);
    }

    @Transactional
    public DomainOfferDtos.DomainOfferOrderResponse markPaid(String orderId) {
        DomainOfferOrder order = requireOrder(orderId);
        boolean becamePaid = applyPaid(order, null, null, null);
        DomainOfferOrder saved = orderRepository.save(order);
        Business business = businessFor(saved.getBusinessId());
        if (becamePaid) {
            messagingService.sendPaymentConfirmation(saved, business);
        }
        audit(null, AuditEventTypes.DOMAIN_OFFER_PAID, saved.getId(),
                "mark_paid", "Ops marked the offer paid");
        return toOrderResponse(saved, business);
    }

    @Transactional
    public DomainOfferDtos.DomainOfferOrderResponse cancel(String orderId, String reason) {
        DomainOfferOrder order = requireOrder(orderId);
        if (order.getStatus() == DomainOfferStatus.DELIVERED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A delivered order cannot be cancelled");
        }
        order.setStatus(DomainOfferStatus.CANCELLED);
        order.setCancelledAt(Instant.now());
        order.setCancelReason(blankToDefault(reason, "Cancelled by Super Admin"));
        order.setNextTouchAt(null);
        DomainOfferOrder saved = orderRepository.save(order);
        audit(null, AuditEventTypes.DOMAIN_OFFER_CANCELLED, saved.getId(),
                "cancelled", saved.getCancelReason());
        return toOrderResponse(saved, businessFor(saved.getBusinessId()));
    }

    @Transactional
    public DomainOfferDtos.DomainOfferOrderResponse refund(String orderId, String note) {
        DomainOfferOrder order = requireOrder(orderId);
        if (!order.getStatus().isPaidLike()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a paid order can be refunded");
        }
        order.setStatus(DomainOfferStatus.REFUNDED);
        order.setNextTouchAt(null);
        if (note != null && !note.isBlank()) {
            order.setFulfilmentNotes(appendNote(order.getFulfilmentNotes(), "Refund: " + note.trim()));
        }
        DomainOfferOrder saved = orderRepository.save(order);
        audit(null, AuditEventTypes.DOMAIN_OFFER_REFUNDED, saved.getId(),
                "refunded", saved.getFulfilmentNotes());
        return toOrderResponse(saved, businessFor(saved.getBusinessId()));
    }

    @Transactional
    public DomainOfferDtos.DomainOfferOrderResponse resendLink(String orderId) {
        DomainOfferOrder order = requireOrder(orderId);
        if (order.getStatus().isClosed() || order.getStatus().isPaidLike()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order does not need a pay link");
        }
        Business business = businessFor(order.getBusinessId());
        DomainOfferMessagingService.DeliveryResult result = messagingService.sendResend(order, business);
        if (result.emailSent()) {
            order.setEmailSentCount(order.getEmailSentCount() + 1);
        }
        if (result.inAppSent()) {
            order.setInAppSentCount(order.getInAppSentCount() + 1);
        }
        return toOrderResponse(orderRepository.save(order), business);
    }

    @Transactional
    public DomainOfferDtos.DomainOfferOrderResponse createRegistrationOrder(String orderId) {
        DomainOfferOrder order = requireOrder(orderId);
        requirePaidForFulfilment(order);
        if (order.getDomainOrderId() != null && !order.getDomainOrderId().isBlank()) {
            return toOrderResponse(order, businessFor(order.getBusinessId()));
        }
        String domain = normalizeDomain(order.effectiveDomain());
        if (!domain.endsWith(".ke")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "New registrations are limited to .ke names. Connect an existing domain instead.");
        }
        DomainOrderResponse created = domainPurchaseService.createOrder(
                order.getBusinessId(), new CreateDomainOrderRequest(domain));
        order.setDomainOrderId(created.id());
        order.setRequestedDomain(domain);
        order.setRegistrationStatus(DomainOfferOrder.REGISTRATION_ORDERED);
        DomainOfferOrder saved = orderRepository.save(order);
        Business business = businessFor(saved.getBusinessId());
        messagingService.sendRegistrationPrompt(saved, business);
        return toOrderResponse(saved, business);
    }

    @Transactional(readOnly = true)
    public DomainOfferDtos.OrderListResponse listOrders(String statusFilter, String q, int limit) {
        DomainOfferCampaign campaign = campaignRepository.findFirstByOrderByCreatedAtDesc().orElse(null);
        if (campaign == null) {
            return new DomainOfferDtos.OrderListResponse(List.of(), 0, stats(null, eligibleFor(null)));
        }
        DomainOfferStatus status = parseStatus(statusFilter);
        int capped = Math.min(Math.max(limit, 1), MAX_ORDERS);
        List<DomainOfferOrder> rows = orderRepository.searchCampaignOrders(
                campaign.getId(), status, blankToNull(q), PageRequest.of(0, capped));
        Map<String, Business> businesses = businesses(rows);
        List<DomainOfferDtos.DomainOfferOrderResponse> out = rows.stream()
                .map(row -> toOrderResponse(row, businesses.get(row.getBusinessId())))
                .toList();
        return new DomainOfferDtos.OrderListResponse(
                out,
                orderRepository.countByCampaignIdAndDeletedAtIsNull(campaign.getId()),
                stats(campaign, eligibleFor(campaign)));
    }

    @Transactional(readOnly = true)
    public DomainOfferDtos.DomainOfferOrderResponse getOrder(String orderId) {
        DomainOfferOrder order = requireOrder(orderId);
        return toOrderResponse(order, businessFor(order.getBusinessId()));
    }

    @Transactional(readOnly = true)
    public DomainOfferMessagingService.DeliveryResult sendTestEmail(
            DomainOfferDtos.TestEmailRequest request,
            String superAdminName
    ) {
        return messagingService.sendTestEmail(request == null ? null : request.email(), superAdminName);
    }

    // ── Scheduler ───────────────────────────────────────────────────────────

    @Transactional
    public int sendDueTouches() {
        DomainOfferCampaign campaign = campaignRepository
                .findFirstByStatusOrderByCreatedAtDesc(DomainOfferCampaignStatus.RUNNING)
                .orElse(null);
        if (campaign == null) {
            return 0;
        }
        List<DomainOfferOrder> due = orderRepository.findDueTouches(
                campaign.getId(),
                campaign.getMaxTouches(),
                Instant.now(),
                PageRequest.of(0, 25));
        if (due.isEmpty()) {
            expireExhausted(campaign);
            completeIfDone(campaign);
            return 0;
        }
        Map<String, Business> businesses = businesses(due);
        int sent = 0;
        for (DomainOfferOrder order : due) {
            try {
                if (sendTouch(order, campaign, businesses.get(order.getBusinessId()))) {
                    sent++;
                }
            } catch (Exception ex) {
                log.warn("Domain offer touch failed order={}: {}", order.getId(), ex.toString());
                order.setLastError(trim(ex.getMessage()));
                order.setNextTouchAt(Instant.now().plus(1, ChronoUnit.HOURS));
                orderRepository.save(order);
            }
        }
        expireExhausted(campaign);
        completeIfDone(campaign);
        return sent;
    }

    private boolean sendTouch(DomainOfferOrder order, DomainOfferCampaign campaign, Business business) {
        int next = order.getTouchCount() + 1;
        List<DomainOfferCopy.Touch> touches = DomainOfferCopy.touches();
        if (next > touches.size() || next > campaign.getMaxTouches()) {
            order.setNextTouchAt(null);
            orderRepository.save(order);
            return false;
        }
        DomainOfferCopy.Touch touch = touches.get(next - 1);
        DomainOfferMessagingService.DeliveryResult result = messagingService.sendTouch(order, business, touch);
        order.setTouchCount(next);
        order.setLastTouchAt(Instant.now());
        order.setLastTouchKind(touch.label().toLowerCase(Locale.ROOT).replace(' ', '_'));
        if (result.emailSent()) {
            order.setEmailSentCount(order.getEmailSentCount() + 1);
        }
        if (result.inAppSent()) {
            order.setInAppSentCount(order.getInAppSentCount() + 1);
        }
        if (result.error() != null) {
            order.setLastEmailError(result.error());
        }
        order.setNextTouchAt(next < campaign.getMaxTouches()
                ? Instant.now().plus(campaign.getIntervalDays(), ChronoUnit.DAYS)
                : null);
        orderRepository.save(order);
        return result.emailSent() || result.inAppSent();
    }

    private void expireExhausted(DomainOfferCampaign campaign) {
        List<DomainOfferOrder> exhausted = orderRepository.findExhausted(
                campaign.getId(),
                campaign.getMaxTouches(),
                Instant.now().minus(30, ChronoUnit.DAYS));
        for (DomainOfferOrder order : exhausted) {
            order.setStatus(DomainOfferStatus.EXPIRED);
            order.setNextTouchAt(null);
            orderRepository.save(order);
        }
    }

    private void completeIfDone(DomainOfferCampaign campaign) {
        if (campaign.getStatus() != DomainOfferCampaignStatus.RUNNING) {
            return;
        }
        long pending = orderRepository.countPendingTouches(campaign.getId(), campaign.getMaxTouches());
        if (pending > 0) {
            return;
        }
        long open = orderRepository.findByCampaignIdAndStatusOrderByCreatedAtAsc(
                        campaign.getId(), DomainOfferStatus.INVITED)
                .stream()
                .filter(o -> o.getNextTouchAt() != null)
                .count();
        if (open == 0) {
            campaign.setStatus(DomainOfferCampaignStatus.COMPLETED);
            campaign.setCompletedAt(Instant.now());
            campaignRepository.save(campaign);
        }
    }

    // ── Payment callback ────────────────────────────────────────────────────

    @Transactional
    public void settleFromStk(String businessId, String orderId, String checkoutId, String txnId, String phone) {
        DomainOfferOrder order = orderRepository.findByIdAndDeletedAtIsNull(orderId).orElse(null);
        if (order == null || (businessId != null && !businessId.equals(order.getBusinessId()))) {
            log.warn("Domain offer STK settled but order not found order={} business={}", orderId, businessId);
            return;
        }
        boolean becamePaid = applyPaid(order, checkoutId, txnId, phone);
        DomainOfferOrder saved = orderRepository.save(order);
        if (becamePaid) {
            messagingService.sendPaymentConfirmation(saved, businessFor(saved.getBusinessId()));
            audit(null, AuditEventTypes.DOMAIN_OFFER_PAID, saved.getId(),
                    "mpesa", "Paid via M-Pesa Express " + (txnId == null ? "" : txnId));
        } else {
            log.warn("Domain offer STK settled but order was not payable order={} status={}",
                    orderId, saved.getStatus());
        }
    }

    @Transactional
    public void markPaymentFailed(String businessId, String orderId, String reason) {
        DomainOfferOrder order = orderRepository.findByIdAndDeletedAtIsNull(orderId).orElse(null);
        if (order == null || (businessId != null && !businessId.equals(order.getBusinessId()))) {
            return;
        }
        if (order.getStatus() != DomainOfferStatus.AWAITING_PAYMENT) {
            return;
        }
        order.setStatus(DomainOfferStatus.INVITED);
        order.setLastStkStatus(GatewayStkPushStatuses.FAILED);
        order.setLastError(trim(reason));
        Instant next = order.getNextTouchAt();
        if (next == null || next.isBefore(Instant.now())) {
            order.setNextTouchAt(Instant.now().plus(1, ChronoUnit.DAYS));
        }
        orderRepository.save(order);
    }

    // ── Public token surface ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PublicDomainOfferDtos.PublicDomainOfferResponse publicOffer(String token) {
        if (isPreviewToken(token)) {
            return previewOffer();
        }
        DomainOfferOrder order = requireByToken(token);
        Business business = businessFor(order.getBusinessId());
        String suggested = order.effectiveDomain();
        if (suggested == null || suggested.isBlank()) {
            suggested = audienceService.suggestedDomain(business == null ? null : business.getSlug());
        }
        String unavailable = paymentUnavailableReason();
        boolean canPay = order.getStatus() == DomainOfferStatus.INVITED && unavailable == null;
        return new PublicDomainOfferDtos.PublicDomainOfferResponse(
                business == null ? "" : business.getName(),
                business == null ? "" : business.getSlug(),
                audienceService.shopHost(business == null ? null : business.getSlug()),
                audienceService.shopUrl(business),
                suggested,
                order.getAmountCents(),
                order.getCurrency(),
                order.getStatus().name(),
                order.getPaidAt() != null,
                order.getStatus() == DomainOfferStatus.DELIVERED,
                order.getDeliveredDomain(),
                order.getRequestedDomain(),
                order.getDomainMode() == null ? DomainOfferDomainMode.OWNED.name() : order.getDomainMode().name(),
                order.getThemeDirection(),
                order.getThemeKey(),
                order.getThemeNotes(),
                maskPhone(order.getPayerPhone() != null ? order.getPayerPhone() : order.getContactPhone()),
                order.getReference(),
                order.getPaidAt(),
                order.getSlaDueAt(),
                statusMessage(order),
                false,
                canPay,
                unavailable,
                CURATED_THEMES,
                directionOptions());
    }

    @Transactional
    public PublicDomainOfferDtos.PublicPayResponse pay(
            String token,
            PublicDomainOfferDtos.PublicPayRequest request
    ) {
        if (isPreviewToken(token)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This is a preview link. Start the campaign to send a payable link to a shop.");
        }
        DomainOfferOrder order = requireByToken(token);
        if (order.getStatus() == DomainOfferStatus.AWAITING_PAYMENT
                && order.getPaymentStartedAt() != null
                && order.getPaymentStartedAt().isAfter(Instant.now().minusSeconds(60))) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "A payment prompt was just sent. Give it a minute before trying again.");
        }
        if (order.getStatus() != DomainOfferStatus.INVITED
                && order.getStatus() != DomainOfferStatus.AWAITING_PAYMENT) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    order.getStatus().isPaidLike()
                            ? "This offer is already paid. We are on it."
                            : "This offer is no longer open.");
        }
        String phone = StkPhoneNormalizer.normalize(request == null ? null : request.phoneNumber());
        if (phone == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid M-Pesa phone number");
        }
        String domain = normalizeDomain(request == null ? null : request.requestedDomain());
        DomainOfferDomainMode mode = parseMode(request == null ? null : request.domainMode());
        if (mode == DomainOfferDomainMode.NEEDS_REGISTRATION && !domain.endsWith(".ke")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "We can register .ke names only. Choose \"I already own this domain\" for other endings.");
        }
        DomainOfferThemeDirection direction = DomainOfferThemeDirection.fromWire(
                request == null ? null : request.themeDirection());
        if (direction == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a theme direction");
        }
        String themeKey = request == null ? null : blankToNull(request.themeKey());
        if (themeKey != null && !StorefrontTemplateIds.isValidStoreThemeId(themeKey)) {
            themeKey = null;
        }

        assertDomainAvailableFor(order, domain);

        order.setRequestedDomain(domain);
        order.setDomainMode(mode);
        order.setThemeDirection(direction.name());
        order.setThemeKey(themeKey == null ? "mart" : themeKey);
        order.setThemeNotes(trimNotes(request == null ? null : request.themeNotes()));
        order.setContactName(blankToDefault(request == null ? null : request.contactName(), order.getContactName()));
        order.setContactPhone(phone);
        order.setStatus(DomainOfferStatus.AWAITING_PAYMENT);
        order.setPaymentStartedAt(Instant.now());
        order.setPayerPhone(phone);
        order.setLastStkStatus(GatewayStkPushStatuses.PENDING);
        order.setLastError(null);

        PlatformStkRailService.Rail rail;
        try {
            rail = platformStkRailService.require("Custom domain + theme offer");
        } catch (ResponseStatusException ex) {
            order.setStatus(DomainOfferStatus.INVITED);
            order.setLastStkStatus(GatewayStkPushStatuses.FAILED);
            order.setLastError(trim(ex.getReason()));
            orderRepository.save(order);
            throw ex;
        }

        BigDecimal amount = BigDecimal.valueOf(order.getAmountCents())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        String reference = "dto-" + order.getReference().toLowerCase(Locale.ROOT) + "-"
                + random(6).toLowerCase(Locale.ROOT);
        String description = "Domain + theme setup";

        gatewayStkPushService.cancelPendingForPhone(
                order.getBusinessId(), phone, "Replaced by domain + theme offer payment");

        PaymentGatewayStkService.StkPushOutcome outcome = paymentGatewayStkService.initiateWithCredentials(
                rail.gatewayType().name(),
                rail.configId(),
                order.getBusinessId(),
                rail.credentialsFor(reference),
                phone,
                amount,
                reference,
                description);

        if (outcome.accepted() && outcome.checkoutRequestId() != null) {
            var push = gatewayStkPushService.registerPush(
                    order.getBusinessId(),
                    rail.gatewayType(),
                    rail.configId(),
                    outcome.checkoutRequestId(),
                    reference,
                    StkPushContextType.DOMAIN_THEME_OFFER,
                    order.getId(),
                    amount,
                    phone);
            order.setStkPushId(push.getId());
            order.setPaymentCheckoutId(outcome.checkoutRequestId());
            order.setLastStkStatus(GatewayStkPushStatuses.PENDING);
            orderRepository.save(order);
            return new PublicDomainOfferDtos.PublicPayResponse(
                    order.getStatus().name(),
                    "Check your phone for the M-Pesa prompt. Enter your PIN to pay KES 1,000.",
                    true,
                    order.getReference());
        }

        order.setStatus(DomainOfferStatus.INVITED);
        order.setLastStkStatus(GatewayStkPushStatuses.FAILED);
        order.setLastError(trim(outcome.message()));
        orderRepository.save(order);
        return new PublicDomainOfferDtos.PublicPayResponse(
                order.getStatus().name(),
                outcome.message() != null ? outcome.message() : "The M-Pesa request was declined. Try again.",
                false,
                order.getReference());
    }

    @Transactional(readOnly = true)
    public PublicDomainOfferDtos.PublicStatusResponse publicStatus(String token) {
        if (isPreviewToken(token)) {
            return new PublicDomainOfferDtos.PublicStatusResponse(
                    DomainOfferStatus.INVITED.name(),
                    false,
                    false,
                    null,
                    null,
                    "Preview only. Start the campaign to send a real, payable link.",
                    null,
                    null,
                    "PREVIEW");
        }
        DomainOfferOrder order = requireByToken(token);
        return new PublicDomainOfferDtos.PublicStatusResponse(
                order.getStatus().name(),
                order.getPaidAt() != null,
                order.getStatus() == DomainOfferStatus.DELIVERED,
                order.getDeliveredDomain(),
                order.getLastStkStatus(),
                statusMessage(order),
                order.getPaidAt(),
                order.getSlaDueAt(),
                order.getReference());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static boolean isPreviewToken(String token) {
        return token != null && PREVIEW_TOKEN.equals(token.trim());
    }

    private static List<PublicDomainOfferDtos.ThemeOption> directionOptions() {
        return Arrays.stream(DomainOfferThemeDirection.values())
                .map(d -> new PublicDomainOfferDtos.ThemeOption(d.name(), d.label(), d.hint()))
                .toList();
    }

    private PublicDomainOfferDtos.PublicDomainOfferResponse previewOffer() {
        return new PublicDomainOfferDtos.PublicDomainOfferResponse(
                "Powermart",
                "powermart",
                "powermart.kiosk.ke",
                "https://powermart.kiosk.ke",
                "powermart.co.ke",
                DomainOfferCampaign.DEFAULT_PRICE_CENTS,
                "KES",
                DomainOfferStatus.INVITED.name(),
                false,
                false,
                null,
                "powermart.co.ke",
                DomainOfferDomainMode.OWNED.name(),
                DomainOfferThemeDirection.WARM_MARKET.name(),
                "mart",
                "Keep the green from our shop sign.",
                null,
                "PREVIEW",
                null,
                null,
                "Preview only. Start the campaign to send a real, payable link.",
                true,
                false,
                "This is a preview link. No M-Pesa prompt is sent.",
                CURATED_THEMES,
                directionOptions());
    }

    private boolean applyPaid(DomainOfferOrder order, String checkoutId, String txnId, String phone) {
        if (order.getStatus().isPaidLike()) {
            if (order.getMpesaReceipt() == null && txnId != null && !txnId.isBlank()) {
                order.setMpesaReceipt(txnId.trim());
            }
            return false;
        }
        if (order.getStatus() == DomainOfferStatus.CANCELLED
                || order.getStatus() == DomainOfferStatus.EXPIRED
                || order.getStatus() == DomainOfferStatus.REFUNDED) {
            if (txnId != null && !txnId.isBlank()) {
                order.setMpesaReceipt(txnId.trim());
            }
            order.setLastStkStatus(GatewayStkPushStatuses.SUCCESS);
            order.setLastError("Payment received after the offer was " + order.getStatus().name().toLowerCase()
                    + " — review for a refund or reopen.");
            return false;
        }
        Instant now = Instant.now();
        order.setStatus(DomainOfferStatus.PAID);
        order.setPaidAt(now);
        order.setSlaDueAt(now.plus(24, ChronoUnit.HOURS));
        order.setNextTouchAt(null);
        order.setLastStkStatus(GatewayStkPushStatuses.SUCCESS);
        order.setLastError(null);
        if (checkoutId != null && !checkoutId.isBlank()) {
            order.setPaymentCheckoutId(checkoutId.trim());
        }
        if (txnId != null && !txnId.isBlank()) {
            order.setMpesaReceipt(txnId.trim());
        }
        if (phone != null && !phone.isBlank()) {
            order.setPayerPhone(phone.trim());
        }
        order.setRegistrationStatus(order.getDomainMode() == DomainOfferDomainMode.NEEDS_REGISTRATION
                ? DomainOfferOrder.REGISTRATION_PENDING
                : DomainOfferOrder.REGISTRATION_NOT_NEEDED);
        return true;
    }

    private void connectDomainInternal(DomainOfferOrder order, String domain) {
        order.setRequestedDomain(domain);
        DomainMapping mapping;
        if (order.getDomainMappingId() != null && !order.getDomainMappingId().isBlank()) {
            mapping = domainMappingRepository.findById(order.getDomainMappingId()).orElse(null);
        } else {
            mapping = domainMappingRepository.findByDomainAndDeletedAtIsNull(domain).orElse(null);
        }
        if (mapping != null && !order.getBusinessId().equals(mapping.getBusinessId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "That domain is already connected to another shop");
        }
        if (mapping == null) {
            DomainResponse added = tenancyService.addDomain(order.getBusinessId(), domain);
            order.setDomainMappingId(added.id());
            mapping = domainMappingRepository.findById(added.id()).orElse(null);
        } else {
            order.setDomainMappingId(mapping.getId());
        }
        try {
            if (mapping != null && mapping.getStatus() != DomainStatus.ACTIVE) {
                tenancyService.verifyDomain(order.getBusinessId(), mapping.getId());
            }
            order.setLastError(null);
            order.setRegistrationStatus(order.getDomainMode() == DomainOfferDomainMode.NEEDS_REGISTRATION
                    ? DomainOfferOrder.REGISTRATION_LIVE
                    : DomainOfferOrder.REGISTRATION_NOT_NEEDED);
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode().is4xxClientError()) {
                throw ex;
            }
            order.setLastError(trim(ex.getReason()));
            order.setRegistrationStatus(order.getDomainMode() == DomainOfferDomainMode.NEEDS_REGISTRATION
                    ? DomainOfferOrder.REGISTRATION_ORDERED
                    : DomainOfferOrder.REGISTRATION_NOT_NEEDED);
        }
        // Make the new address the shop's default once the mapping is live.
        DomainMapping refreshed = order.getDomainMappingId() == null
                ? null
                : domainMappingRepository.findById(order.getDomainMappingId()).orElse(null);
        if (refreshed != null && refreshed.getStatus() == DomainStatus.ACTIVE) {
            try {
                tenancyService.setPrimaryDomain(order.getBusinessId(), refreshed.getId());
            } catch (Exception ex) {
                log.debug("Domain offer primary promote skipped order={}: {}", order.getId(), ex.getMessage());
            }
        }
        if (order.getStatus() == DomainOfferStatus.PAID) {
            order.setStatus(DomainOfferStatus.IN_PROGRESS);
            order.setStartedAt(Instant.now());
        }
    }

    private void assertDomainAvailableFor(DomainOfferOrder order, String domain) {
        domainMappingRepository.findByDomainAndDeletedAtIsNull(domain).ifPresent(existing -> {
            if (!order.getBusinessId().equals(existing.getBusinessId())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "That domain is already connected to another shop");
            }
        });
    }

    private String normalizeDomain(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the domain you want");
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        value = value.replaceFirst("^[a-z]+://", "");
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        if (value.startsWith("www.")) {
            value = value.substring(4);
        }
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            value = IDN.toASCII(value, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That domain is not valid");
        }
        if (value.isBlank() || value.length() > 253 || !value.contains(".")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Use a full domain, for example powermart.co.ke");
        }
        if (!value.matches("[a-z0-9.-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That domain is not valid");
        }
        if (value.contains("..") || value.startsWith("-") || value.endsWith("-")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That domain is not valid");
        }
        for (String label : value.split("\\.")) {
            if (label.isBlank() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That domain is not valid");
            }
        }
        reservedHostnameGuard.assertClaimable(value);
        return value;
    }

    private static DomainOfferDomainMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return DomainOfferDomainMode.OWNED;
        }
        try {
            return DomainOfferDomainMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose whether you own the domain");
        }
    }

    private DomainOfferStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return DomainOfferStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status filter");
        }
    }

    private DomainOfferOrder requireByToken(String token) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Offer not found");
        }
        return orderRepository.findByPayTokenAndDeletedAtIsNull(token.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Offer not found"));
    }

    private DomainOfferOrder requireOrder(String orderId) {
        return orderRepository.findByIdAndDeletedAtIsNull(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Offer order not found"));
    }

    private DomainOfferCampaign requireLatestCampaign() {
        return campaignRepository.findFirstByOrderByCreatedAtDesc()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Start the offer first"));
    }

    private void requirePaidForFulfilment(DomainOfferOrder order) {
        if (!order.getStatus().isPaidLike()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Only a paid order can be fulfilled (status=" + order.getStatus() + ")");
        }
    }

    private Business businessFor(String businessId) {
        return businessRepository.findByIdAndDeletedAtIsNull(businessId).orElse(null);
    }

    private Map<String, Business> businesses(List<DomainOfferOrder> rows) {
        Set<String> ids = new HashSet<>();
        for (DomainOfferOrder row : rows) {
            ids.add(row.getBusinessId());
        }
        Map<String, Business> out = new HashMap<>();
        for (Business business : businessRepository.findAllById(ids)) {
            out.put(business.getId(), business);
        }
        return out;
    }

    private DomainOfferDtos.DomainOfferStats stats(DomainOfferCampaign campaign, long eligible) {
        if (campaign == null) {
            return new DomainOfferDtos.DomainOfferStats(
                    eligible, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        String id = campaign.getId();
        long invited = orderRepository.countByCampaignIdAndStatusAndDeletedAtIsNull(id, DomainOfferStatus.INVITED);
        long awaiting = orderRepository.countByCampaignIdAndStatusAndDeletedAtIsNull(
                id, DomainOfferStatus.AWAITING_PAYMENT);
        long paid = orderRepository.countByCampaignIdAndStatusAndDeletedAtIsNull(id, DomainOfferStatus.PAID);
        long inProgress = orderRepository.countByCampaignIdAndStatusAndDeletedAtIsNull(
                id, DomainOfferStatus.IN_PROGRESS);
        long delivered = orderRepository.countByCampaignIdAndStatusAndDeletedAtIsNull(
                id, DomainOfferStatus.DELIVERED);
        long cancelled = orderRepository.countByCampaignIdAndStatusAndDeletedAtIsNull(
                id, DomainOfferStatus.CANCELLED);
        long expired = orderRepository.countByCampaignIdAndStatusAndDeletedAtIsNull(
                id, DomainOfferStatus.EXPIRED);
        long overdue = orderRepository.countOverdue(id, Instant.now());
        long dueNow = orderRepository.countDueTouches(id, campaign.getMaxTouches(), Instant.now());
        long revenue = orderRepository.sumAmountForStatuses(
                id,
                List.of(DomainOfferStatus.PAID, DomainOfferStatus.IN_PROGRESS, DomainOfferStatus.DELIVERED));
        long paidLike = paid + inProgress + delivered;
        int conversion = campaign.getRecipientsTargeted() <= 0
                ? 0
                : (int) Math.round(paidLike * 100.0 / campaign.getRecipientsTargeted());
        return new DomainOfferDtos.DomainOfferStats(
                eligible, invited, awaiting, paid, inProgress, delivered, cancelled, expired,
                overdue, dueNow, revenue, conversion);
    }

    private DomainOfferDtos.DomainOfferCampaignResponse toCampaignResponse(
            DomainOfferCampaign campaign,
            DomainOfferDtos.DomainOfferStats stats
    ) {
        if (campaign == null) {
            return new DomainOfferDtos.DomainOfferCampaignResponse(
                    null,
                    "Own your address — KES 1,000",
                    DomainOfferCampaignStatus.DRAFT.name(),
                    DomainOfferCampaign.AUDIENCE_NO_CUSTOM_DOMAIN,
                    DomainOfferCampaign.DEFAULT_PRICE_CENTS,
                    "KES",
                    DomainOfferCampaign.DEFAULT_INTERVAL_DAYS,
                    DomainOfferCampaign.DEFAULT_MAX_TOUCHES,
                    true,
                    true,
                    0,
                    null, null, null, null,
                    stats,
                    touches());
        }
        return new DomainOfferDtos.DomainOfferCampaignResponse(
                campaign.getId(),
                campaign.getName(),
                campaign.getStatus().name(),
                campaign.getAudienceKey(),
                campaign.getPriceCents(),
                campaign.getCurrency(),
                campaign.getIntervalDays(),
                campaign.getMaxTouches(),
                campaign.isEmailEnabled(),
                campaign.isInAppEnabled(),
                campaign.getRecipientsTargeted(),
                campaign.getStartedAt(),
                campaign.getPausedAt(),
                campaign.getCompletedAt(),
                campaign.getCreatedAt(),
                stats,
                touches());
    }

    private DomainOfferDtos.DomainOfferCampaignResponse getCampaignResponse(DomainOfferCampaign campaign) {
        return toCampaignResponse(campaign, stats(campaign, eligibleFor(campaign)));
    }

    /**
     * Before launch this is the live audience. After launch the enrolled count is the
     * meaningful denominator: those shops now have a recent offer, so re-resolving the
     * audience would (correctly) exclude them and show an empty campaign.
     */
    private long eligibleFor(DomainOfferCampaign campaign) {
        if (campaign != null && campaign.getRecipientsTargeted() > 0) {
            return campaign.getRecipientsTargeted();
        }
        return audienceService.resolve().size();
    }

    private DomainOfferDtos.DomainOfferOrderResponse toOrderResponse(DomainOfferOrder order, Business business) {
        String payLink = business == null ? null : messagingService.payUrl(order, business);
        return new DomainOfferDtos.DomainOfferOrderResponse(
                order.getId(),
                order.getReference(),
                order.getBusinessId(),
                business == null ? null : business.getName(),
                business == null ? null : business.getSlug(),
                order.getStatus().name(),
                order.getDomainMode() == null ? null : order.getDomainMode().name(),
                order.getRequestedDomain(),
                order.getSuggestedDomain(),
                order.getThemeKey(),
                order.getThemeDirection(),
                order.getThemeNotes(),
                order.getContactName(),
                order.getContactEmail(),
                order.getContactPhone(),
                order.getAmountCents(),
                order.getCurrency(),
                order.getTouchCount(),
                order.getLastTouchAt(),
                order.getNextTouchAt(),
                order.getEmailSentCount(),
                order.getInAppSentCount(),
                order.getLastEmailError(),
                order.getLastStkStatus(),
                order.getMpesaReceipt(),
                order.getPayerPhone(),
                order.getPaymentStartedAt(),
                order.getPaidAt(),
                order.getSlaDueAt(),
                order.getSlaDueAt() != null
                        && order.getSlaDueAt().isBefore(Instant.now())
                        && (order.getStatus() == DomainOfferStatus.PAID
                        || order.getStatus() == DomainOfferStatus.IN_PROGRESS),
                order.getAssignedTo(),
                order.getStartedAt(),
                order.getDeliveredAt(),
                order.getDeliveredDomain(),
                order.getDomainMappingId(),
                order.getDomainOrderId(),
                order.getRegistrationStatus(),
                order.getFulfilmentNotes(),
                order.getLastError(),
                payLink,
                order.getCreatedAt(),
                order.getUpdatedAt());
    }

    private String statusMessage(DomainOfferOrder order) {
        return switch (order.getStatus()) {
            case INVITED -> "Pay KES 1,000 to start. We connect the domain and customise the theme within 24 hours.";
            case AWAITING_PAYMENT -> "Check your phone for the M-Pesa prompt. It can take a few seconds to arrive.";
            case PAID -> "Payment received. The 24-hour setup clock is running.";
            case IN_PROGRESS -> "We are setting it up now.";
            case DELIVERED -> order.getDeliveredDomain() == null
                    ? "Your shop is live on its own domain."
                    : "Your shop is live at " + order.getDeliveredDomain() + ".";
            case CANCELLED -> "This offer was cancelled.";
            case EXPIRED -> "This offer has expired. Ask us to reopen it.";
            case REFUNDED -> "This offer was refunded.";
        };
    }

    private String paymentUnavailableReason() {
        try {
            return platformStkRailService.resolve().isPresent()
                    ? null
                    : "M-Pesa is not connected yet. Ask Kiosk support to finish the platform rail.";
        } catch (Exception ex) {
            return "M-Pesa is temporarily unavailable. Try again in a moment.";
        }
    }

    private String registrationStatusForDelivered(DomainOfferOrder order) {
        if (order.getDomainMode() != DomainOfferDomainMode.NEEDS_REGISTRATION) {
            return DomainOfferOrder.REGISTRATION_NOT_NEEDED;
        }
        if (order.getDomainMappingId() != null && !order.getDomainMappingId().isBlank()) {
            DomainMapping mapping = domainMappingRepository.findById(order.getDomainMappingId()).orElse(null);
            if (mapping != null && mapping.getStatus() == DomainStatus.ACTIVE) {
                return DomainOfferOrder.REGISTRATION_LIVE;
            }
        }
        return DomainOfferOrder.REGISTRATION_ORDERED;
    }

    private void audit(String actorId, String type, String orderId, String reason, String detail) {
        try {
            auditEventPublisher.publishSynchronous(auditEventBuilder
                    .builder(AuditEventCategory.SYSTEM, type, AuditEventSeverity.INFO)
                    .businessId("platform")
                    .actor(actorId, actorId == null ? AuditEventActorType.SYSTEM : AuditEventActorType.USER)
                    .actorName(actorId == null ? "system" : "super_admin")
                    .target("domain_offer_order", orderId)
                    .targetLabel(reason)
                    .source(actorId == null ? "scheduler" : "super_admin_portal")
                    .reason(detail)
                    .build());
        } catch (Exception ex) {
            log.debug("Domain offer audit skipped: {}", ex.getMessage());
        }
    }

    private static String reference() {
        return "DTO-" + random(6);
    }

    private static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String random(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(REFERENCE_ALPHABET.charAt(RANDOM.nextInt(REFERENCE_ALPHABET.length())));
        }
        return out.toString();
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 6) {
            return "****";
        }
        return "***" + digits.substring(digits.length() - 3);
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() > 480 ? value.substring(0, 480) : value;
    }

    private static String trimNotes(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > 1000 ? trimmed.substring(0, 1000) : trimmed;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String appendNote(String existing, String note) {
        if (existing == null || existing.isBlank()) {
            return note;
        }
        return existing + "\n" + note;
    }

}
