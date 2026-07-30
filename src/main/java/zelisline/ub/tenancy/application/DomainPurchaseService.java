package zelisline.ub.tenancy.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.application.GatewayStkPushService;
import zelisline.ub.payments.application.PaymentGatewayStkService;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.payments.domain.GatewayStkPushStatuses;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.StkPushContextType;
import zelisline.ub.platform.application.PlatformDomainSettingsService;
import zelisline.ub.tenancy.api.dto.CreateDomainOrderRequest;
import zelisline.ub.tenancy.api.dto.DomainOrderResponse;
import zelisline.ub.tenancy.api.dto.DomainSearchResponse;
import zelisline.ub.tenancy.api.dto.PayDomainOrderRequest;
import zelisline.ub.tenancy.api.dto.PayDomainOrderResponse;
import zelisline.ub.tenancy.domain.DomainMapping;
import zelisline.ub.tenancy.domain.DomainOrder;
import zelisline.ub.tenancy.domain.DomainOrderStatus;
import zelisline.ub.tenancy.domain.DomainNsStatus;
import zelisline.ub.tenancy.integrations.hostafrica.HostAfricaClient;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;
import zelisline.ub.tenancy.repository.DomainOrderRepository;

/**
 * Merchant buy flow: search Kenyan TLDs → create order → wait for HA ownership → provision.
 * Palmart billing is stubbed when Super Admin enables the HostAfrica billing stub;
 * otherwise platform M-Pesa STK (or ops mark-paid) settles into {@link #markPaid}.
 */
@Service
@RequiredArgsConstructor
public class DomainPurchaseService {

    private final HostAfricaClient hostAfricaClient;
    private final PlatformDomainSettingsService domainSettingsService;
    private final DomainOrderRepository domainOrderRepository;
    private final DomainMappingRepository domainMappingRepository;
    private final BusinessRepository businessRepository;
    private final ReservedHostnameGuard reservedHostnameGuard;
    private final DomainProvisioningService domainProvisioningService;
    private final PaymentGatewayStkService paymentGatewayStkService;
    private final GatewayStkPushService gatewayStkPushService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public DomainSearchResponse search(String businessId, String rawQuery) {
        requireBusiness(businessId);
        requireHostAfrica();
        String query = normalizeQuery(rawQuery);
        List<String> candidates = expandKenyanCandidates(query);
        var result = hostAfricaClient.checkAvailability(String.join(",", candidates));
        if (result.skipped()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "HostAfrica is not configured");
        }
        if (!result.ok()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "HostAfrica search failed: " + result.error());
        }

        List<DomainSearchResponse.DomainQuoteDto> quotes = new ArrayList<>(result.quotes().stream()
                .filter(q -> q.domain() != null)
                .filter(this::isKenyanTld)
                .map(q -> new DomainSearchResponse.DomainQuoteDto(
                        q.domain(),
                        q.available(),
                        q.status(),
                        q.priceCents(),
                        q.currency(),
                        q.periodYears(),
                        q.premium(),
                        q.requiresAdditionalInfo()
                ))
                .toList());

        // If bare search returned nothing useful, try suggest.
        List<String> suggestions = new ArrayList<>(result.suggestions());
        if (quotes.isEmpty() || suggestions.isEmpty()) {
            var suggested = hostAfricaClient.suggest(stripTld(query));
            if (suggested.ok()) {
                suggestions.addAll(suggested.suggestions());
                for (var q : suggested.quotes()) {
                    if (q.domain() != null && isKenyanTld(q)
                            && quotes.stream().noneMatch(x -> x.domain().equals(q.domain()))) {
                        quotes.add(new DomainSearchResponse.DomainQuoteDto(
                                q.domain(),
                                q.available(),
                                q.status(),
                                q.priceCents(),
                                q.currency(),
                                q.periodYears(),
                                q.premium(),
                                q.requiresAdditionalInfo()
                        ));
                    }
                }
            }
        }

        return new DomainSearchResponse(
                query,
                result.currency(),
                quotes,
                suggestions.stream().distinct().limit(12).toList(),
                null
        );
    }

    @Transactional
    public DomainOrderResponse createOrder(String businessId, CreateDomainOrderRequest request) {
        requireBusiness(businessId);
        requireHostAfrica();
        String fqdn = normalizeQuery(request == null ? null : request.fqdn());
        if (!fqdn.contains(".")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide a full domain including TLD (e.g. shop.co.ke)");
        }
        if (!isKenyanTld(fqdn)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only Kenyan TLDs (.ke / .co.ke / …) are supported");
        }
        reservedHostnameGuard.assertClaimable(fqdn);
        if (domainMappingRepository.findByDomainAndDeletedAtIsNull(fqdn).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Domain is already mapped in Palmart");
        }
        domainOrderRepository.findFirstByFqdnAndDeletedAtIsNullOrderByCreatedAtDesc(fqdn)
                .filter(o -> o.getDeletedAt() == null)
                .filter(o -> o.getStatus() != DomainOrderStatus.FAILED && o.getStatus() != DomainOrderStatus.LIVE)
                .ifPresent(o -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "An open order already exists for " + fqdn);
                });

        var availability = hostAfricaClient.checkAvailability(fqdn);
        if (!availability.ok() || availability.quotes().isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not quote domain: " + (availability.error() == null ? "no quote" : availability.error())
            );
        }
        var quote = availability.quotes().getFirst();
        if (!quote.available()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Domain is not available: " + fqdn);
        }

        DomainOrder order = new DomainOrder();
        order.setBusinessId(businessId);
        order.setFqdn(fqdn);
        order.setPriceCents(quote.priceCents());
        order.setCurrency(quote.currency());
        order.setRegisterUrl(quote.registerUrl());
        order.setNsStatus(DomainNsStatus.PENDING_OPS);

        if (domainSettingsService.resolve().hostafricaBillingStubEnabled()) {
            // Merchant confirm = paid; registration happens on platform HA account via register_url / ops.
            order.setStatus(DomainOrderStatus.REGISTERING);
        } else {
            order.setStatus(DomainOrderStatus.AWAITING_PAYMENT);
        }
        DomainOrder saved = domainOrderRepository.save(order);
        if (saved.getStatus() == DomainOrderStatus.REGISTERING) {
            refreshRegisterUrl(saved);
            saved = domainOrderRepository.save(saved);
        }
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<DomainOrderResponse> listOrders(String businessId) {
        requireBusiness(businessId);
        return domainOrderRepository.findByBusinessIdAndDeletedAtIsNullOrderByCreatedAtDesc(businessId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public DomainOrderResponse getOrder(String businessId, String orderId) {
        return toResponse(requireOrder(businessId, orderId));
    }

    /**
     * Advances one order: ownership poll → Vercel provision → NS cutover → verify/live.
     */
    @Transactional
    public DomainOrderResponse syncOrder(String businessId, String orderId) {
        DomainOrder order = requireOrder(businessId, orderId);
        if (order.getStatus() == DomainOrderStatus.LIVE) {
            return toResponse(order);
        }
        if (order.getStatus() == DomainOrderStatus.FAILED) {
            return toResponse(order);
        }
        if (order.getStatus() == DomainOrderStatus.AWAITING_PAYMENT
                || order.getStatus() == DomainOrderStatus.QUOTED) {
            pollPendingDomainStk(order);
            return toResponse(requireOrder(businessId, orderId));
        }

        try {
            if (order.getStatus() == DomainOrderStatus.REGISTERING) {
                pollOwnership(order);
            }
            if (order.getStatus() == DomainOrderStatus.OWNED
                    || order.getStatus() == DomainOrderStatus.PROVISIONING) {
                domainProvisioningService.provision(order);
            }
        } catch (ResponseStatusException ex) {
            order.setLastError(ex.getReason());
            if (ex.getStatusCode().is5xxServerError() || ex.getStatusCode() == HttpStatus.BAD_GATEWAY) {
                // Keep retryable status; don't mark FAILED for transient HA/Vercel issues.
                domainOrderRepository.save(order);
                throw ex;
            }
            order.setStatus(DomainOrderStatus.FAILED);
            domainOrderRepository.save(order);
            throw ex;
        } catch (Exception ex) {
            order.setLastError(ex.getMessage());
            order.setStatus(DomainOrderStatus.FAILED);
            domainOrderRepository.save(order);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Provisioning failed: " + ex.getMessage());
        }
        return toResponse(domainOrderRepository.save(order));
    }

    @Transactional
    public int syncOpenOrders() {
        List<DomainOrder> open = domainOrderRepository.findOpenByStatuses(List.of(
                DomainOrderStatus.REGISTERING,
                DomainOrderStatus.OWNED,
                DomainOrderStatus.PROVISIONING
        ));
        int advanced = 0;
        for (DomainOrder order : open) {
            try {
                syncOrder(order.getBusinessId(), order.getId());
                advanced++;
            } catch (Exception ignored) {
                // Individual failures are persisted on the order; continue batch.
            }
        }
        return advanced;
    }

    @Transactional(readOnly = true)
    public List<DomainOrderResponse> listAllOrders(String statusFilter) {
        List<DomainOrder> rows;
        if (statusFilter != null && !statusFilter.isBlank()) {
            DomainOrderStatus status;
            try {
                status = DomainOrderStatus.valueOf(statusFilter.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status filter");
            }
            rows = domainOrderRepository.findByDeletedAtIsNullAndStatusOrderByUpdatedAtDesc(status);
        } else {
            rows = domainOrderRepository.findByDeletedAtIsNullOrderByUpdatedAtDesc();
        }
        return rows.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public DomainOrderResponse getOrderForSuperAdmin(String orderId) {
        return toResponse(requireOrderAny(orderId));
    }

    /**
     * Marks an order paid (ops / STK settlement). Moves {@code AWAITING_PAYMENT} → {@code REGISTERING}.
     */
    @Transactional
    public DomainOrderResponse markPaid(String orderId) {
        return markPaid(orderId, null, null, null);
    }

    @Transactional
    public DomainOrderResponse markPaid(String orderId, String checkoutId, String txnId, String payerPhone) {
        DomainOrder order = requireOrderAny(orderId);
        if (order.getStatus() == DomainOrderStatus.LIVE
                || order.getStatus() == DomainOrderStatus.REGISTERING
                || order.getStatus() == DomainOrderStatus.OWNED
                || order.getStatus() == DomainOrderStatus.PROVISIONING) {
            if (order.getPaidAt() == null) {
                order.setPaidAt(Instant.now());
            }
            if (checkoutId != null && !checkoutId.isBlank()) {
                order.setPaymentCheckoutId(checkoutId.trim());
            }
            if (txnId != null && !txnId.isBlank()) {
                order.setPaymentTxnId(txnId.trim());
            }
            if (payerPhone != null && !payerPhone.isBlank()) {
                order.setPayerPhone(payerPhone.trim());
            }
            order.setLastStkStatus(GatewayStkPushStatuses.SUCCESS);
            return toResponse(domainOrderRepository.save(order));
        }
        if (order.getStatus() != DomainOrderStatus.AWAITING_PAYMENT
                && order.getStatus() != DomainOrderStatus.QUOTED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Order is not awaiting payment (status=" + order.getStatus() + ")"
            );
        }
        order.setStatus(DomainOrderStatus.REGISTERING);
        order.setPaidAt(Instant.now());
        order.setLastError(null);
        order.setLastStkStatus(GatewayStkPushStatuses.SUCCESS);
        if (checkoutId != null && !checkoutId.isBlank()) {
            order.setPaymentCheckoutId(checkoutId.trim());
        }
        if (txnId != null && !txnId.isBlank()) {
            order.setPaymentTxnId(txnId.trim());
        }
        if (payerPhone != null && !payerPhone.isBlank()) {
            order.setPayerPhone(payerPhone.trim());
        }
        refreshRegisterUrl(order);
        if (order.getRegisterUrl() == null || order.getRegisterUrl().isBlank()) {
            order.setLastError("Paid — open HostAfrica checkout once register_url is available (Refresh register URL).");
        } else {
            order.setLastError("Paid — complete HostAfrica registration via register_url, then Sync.");
        }
        DomainOrder saved = domainOrderRepository.save(order);
        return toResponse(saved);
    }

    /** Called when DOMAIN_ORDER STK succeeds. Idempotent. */
    @Transactional
    public void settleFromStk(String businessId, String orderId, String checkoutId, String txnId, String phone) {
        DomainOrder order = requireOrder(businessId, orderId);
        markPaid(order.getId(), checkoutId, txnId, phone);
    }

    @Transactional
    public void markStkFailed(String businessId, String orderId, String reason) {
        DomainOrder order = requireOrder(businessId, orderId);
        if (order.getStatus() != DomainOrderStatus.AWAITING_PAYMENT
                && order.getStatus() != DomainOrderStatus.QUOTED) {
            return;
        }
        order.setLastStkStatus(GatewayStkPushStatuses.FAILED);
        if (reason != null && !reason.isBlank()) {
            order.setLastError(reason.trim());
        }
        domainOrderRepository.save(order);
    }

    /**
     * Merchant M-Pesa STK against Palmart platform KopoKopo till.
     */
    @Transactional
    public PayDomainOrderResponse initiatePayment(String businessId, String orderId, PayDomainOrderRequest request) {
        DomainOrder order = requireOrder(businessId, orderId);
        if (order.getStatus() != DomainOrderStatus.AWAITING_PAYMENT
                && order.getStatus() != DomainOrderStatus.QUOTED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Order is not awaiting payment (status=" + order.getStatus() + ")"
            );
        }
        if (order.getPriceCents() == null || order.getPriceCents() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order has no price to charge");
        }
        if (!domainSettingsService.palmartStkConfigured()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Platform M-Pesa is not configured. Ask Super Admin to add Palmart STK under Platform → Domains."
            );
        }
        String phone = StkPhoneNormalizer.normalize(request == null ? null : request.phoneNumber());
        if (phone == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid M-Pesa phone number");
        }

        BigDecimal amount = BigDecimal.valueOf(order.getPriceCents())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        String reference = "do-" + order.getId().replace("-", "").substring(0, 12)
                + "-" + UUID.randomUUID().toString().substring(0, 6);
        String description = "Domain " + order.getFqdn();

        gatewayStkPushService.cancelPendingForPhone(businessId, phone, "Replaced by domain order payment");

        Map<String, String> creds = domainSettingsService.resolvePalmartStkCredentials();
        PaymentGatewayStkService.StkPushOutcome outcome = paymentGatewayStkService.initiateWithCredentials(
                GatewayType.KOPOKOPO.name(),
                PlatformDomainSettingsService.PLATFORM_DOMAIN_STK_CONFIG_ID,
                businessId,
                creds,
                phone,
                amount,
                reference,
                description
        );

        if (outcome.accepted() && outcome.checkoutRequestId() != null) {
            gatewayStkPushService.registerPush(
                    businessId,
                    GatewayType.KOPOKOPO,
                    PlatformDomainSettingsService.PLATFORM_DOMAIN_STK_CONFIG_ID,
                    outcome.checkoutRequestId(),
                    reference,
                    StkPushContextType.DOMAIN_ORDER,
                    order.getId(),
                    amount,
                    phone
            );
            order.setPayerPhone(phone);
            order.setPaymentCheckoutId(outcome.checkoutRequestId());
            order.setLastStkStatus(GatewayStkPushStatuses.PENDING);
            order.setLastError(null);
            DomainOrder saved = domainOrderRepository.save(order);
            return new PayDomainOrderResponse(
                    saved.getId(),
                    outcome.checkoutRequestId(),
                    GatewayStkPushStatuses.PENDING,
                    outcome.message(),
                    true,
                    toResponse(saved)
            );
        }

        order.setLastStkStatus(GatewayStkPushStatuses.FAILED);
        order.setLastError(outcome.message());
        DomainOrder saved = domainOrderRepository.save(order);
        return new PayDomainOrderResponse(
                saved.getId(),
                null,
                GatewayStkPushStatuses.FAILED,
                outcome.message() != null ? outcome.message() : "Payment request declined",
                false,
                toResponse(saved)
        );
    }

    /** Ops: nameservers confirmed at registrar (or HA API succeeded elsewhere). */
    @Transactional
    public DomainOrderResponse markNsActive(String orderId) {
        DomainOrder order = requireOrderAny(orderId);
        order.setNsStatus(DomainNsStatus.ACTIVE);
        if (order.getLastError() != null && order.getLastError().toLowerCase(Locale.ROOT).contains("ns")) {
            order.setLastError(null);
        }
        DomainOrder saved = domainOrderRepository.save(order);
        // Re-run provision/verify once NS is known good.
        if (saved.getStatus() == DomainOrderStatus.OWNED
                || saved.getStatus() == DomainOrderStatus.PROVISIONING
                || saved.getStatus() == DomainOrderStatus.REGISTERING) {
            return syncOrder(saved.getBusinessId(), saved.getId());
        }
        return toResponse(saved);
    }

    @Transactional
    public DomainOrderResponse syncOrderForSuperAdmin(String orderId) {
        DomainOrder order = requireOrderAny(orderId);
        return syncOrder(order.getBusinessId(), order.getId());
    }

    /**
     * Attach HostAfrica domain id manually when list poll is slow (ops paste from HA panel).
     */
    @Transactional
    public DomainOrderResponse attachHostafricaId(String orderId, String hostafricaDomainId) {
        DomainOrder order = requireOrderAny(orderId);
        if (hostafricaDomainId == null || hostafricaDomainId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "hostafricaDomainId is required");
        }
        order.setHostafricaDomainId(hostafricaDomainId.trim());
        if (order.getStatus() == DomainOrderStatus.REGISTERING) {
            order.setStatus(DomainOrderStatus.OWNED);
            order.setLastError(null);
        }
        DomainOrder saved = domainOrderRepository.save(order);
        return syncOrder(saved.getBusinessId(), saved.getId());
    }

    /** Refresh HostAfrica checkout link for ops (REGISTERING orders). */
    @Transactional
    public DomainOrderResponse refreshRegisterUrlForSuperAdmin(String orderId) {
        DomainOrder order = requireOrderAny(orderId);
        if (order.getStatus() != DomainOrderStatus.REGISTERING
                && order.getStatus() != DomainOrderStatus.AWAITING_PAYMENT
                && order.getStatus() != DomainOrderStatus.QUOTED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Refresh register URL only applies before ownership (status=" + order.getStatus() + ")"
            );
        }
        refreshRegisterUrl(order);
        if (order.getRegisterUrl() == null || order.getRegisterUrl().isBlank()) {
            order.setLastError("HostAfrica did not return a register_url — domain may already be taken or quote expired.");
        } else if (order.getStatus() == DomainOrderStatus.REGISTERING) {
            order.setLastError("Open HostAfrica register_url to complete purchase on the platform account, then Sync.");
        }
        return toResponse(domainOrderRepository.save(order));
    }

    private void refreshRegisterUrl(DomainOrder order) {
        if (order.getFqdn() == null || order.getFqdn().isBlank()) {
            return;
        }
        var availability = hostAfricaClient.checkAvailability(order.getFqdn());
        if (!availability.ok() || availability.quotes().isEmpty()) {
            return;
        }
        var quote = availability.quotes().getFirst();
        if (quote.registerUrl() != null && !quote.registerUrl().isBlank()) {
            order.setRegisterUrl(quote.registerUrl());
        }
        if (quote.priceCents() != null) {
            order.setPriceCents(quote.priceCents());
        }
        if (quote.currency() != null && !quote.currency().isBlank()) {
            order.setCurrency(quote.currency());
        }
    }

    private void pollOwnership(DomainOrder order) {
        var owned = hostAfricaClient.findOwnedByFqdn(order.getFqdn());
        if (owned.isEmpty()) {
            if (order.getRegisterUrl() != null && !order.getRegisterUrl().isBlank()) {
                order.setLastError("Waiting for HostAfrica ownership — complete register_url checkout on the platform account, then Sync.");
            } else {
                order.setLastError("Waiting for domain to appear on HostAfrica account");
            }
            domainOrderRepository.save(order);
            return;
        }
        var d = owned.get();
        order.setHostafricaDomainId(d.domainId());

        // Best-effort: bind WHOIS roles to platform HA client profile.
        var contacts = hostAfricaClient.updateContactsToOwner(d.domainId());
        if (!contacts.ok() && !contacts.skipped()) {
            order.setLastError("HostAfrica contacts: " + contacts.error());
        }

        // Complete extra TLD fields when HA already has values; otherwise surface field names for ops.
        String requiredMsg = tryCompleteRequiredData(d.domainId(), order.getFqdn());
        if (requiredMsg != null) {
            order.setLastError(requiredMsg);
            domainOrderRepository.save(order);
            return;
        }

        if (!d.active()) {
            order.setLastError("HostAfrica status: " + d.status() + " — waiting for Active after registration.");
            domainOrderRepository.save(order);
            return;
        }
        order.setStatus(DomainOrderStatus.OWNED);
        order.setLastError(null);
        domainOrderRepository.save(order);
    }

    /**
     * @return human message if still blocked on required data; null if clear or not applicable
     */
    private String tryCompleteRequiredData(String domainId, String fqdn) {
        var requiring = hostAfricaClient.listDomainsRequiringData();
        if (!requiring.ok() || requiring.skipped()) {
            return null;
        }
        String needle = fqdn == null ? null : fqdn.trim().toLowerCase(Locale.ROOT);
        var match = requiring.domains().stream()
                .filter(d -> domainId != null && domainId.equals(d.domainId())
                        || (needle != null && needle.equals(d.domain())))
                .findFirst();
        if (match.isEmpty()) {
            return null;
        }
        var row = match.get();
        Map<String, String> defaults = domainSettingsService.resolveRegistrantDefaults();
        Map<String, String> fields = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (var f : row.additionalFields()) {
            if (f.name() == null || f.name().isBlank()) {
                continue;
            }
            String name = f.name().trim();
            if (f.value() != null && !f.value().isBlank()) {
                fields.put(name, f.value().trim());
                continue;
            }
            String fromDefaults = defaults.get(name);
            if (fromDefaults == null || fromDefaults.isBlank()) {
                // Case-insensitive key match for SA convenience.
                fromDefaults = defaults.entrySet().stream()
                        .filter(e -> e.getKey().equalsIgnoreCase(name))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .orElse(null);
            }
            if (fromDefaults != null && !fromDefaults.isBlank()) {
                fields.put(name, fromDefaults.trim());
            } else if (f.required()) {
                missing.add(f.displayName() != null && !f.displayName().isBlank() ? f.displayName() : name);
            }
        }
        if (!missing.isEmpty()) {
            return "HostAfrica needs registrant data — set defaults under Platform → Domains, or complete in HA panel: "
                    + String.join(", ", missing);
        }
        if (fields.isEmpty()) {
            return "HostAfrica lists this domain as requiring data — complete fields in HA client area.";
        }
        var saved = hostAfricaClient.saveDomainRequiredData(domainId, fields);
        if (!saved.ok() && !saved.skipped()) {
            return "HostAfrica required-data save failed: " + saved.error();
        }
        return null;
    }

    private void pollPendingDomainStk(DomainOrder order) {
        if (!GatewayStkPushStatuses.PENDING.equalsIgnoreCase(
                order.getLastStkStatus() == null ? "" : order.getLastStkStatus())) {
            return;
        }
        if (order.getPaymentCheckoutId() == null || order.getPaymentCheckoutId().isBlank()) {
            return;
        }
        gatewayStkPushService
                .findByCheckoutId(GatewayType.KOPOKOPO, order.getPaymentCheckoutId())
                .ifPresent(push -> gatewayStkPushService.pollAndUpdate(push));
    }

    DomainOrderResponse toResponse(DomainOrder order) {
        Map<String, Object> instructions = new LinkedHashMap<>();
        List<String> intended = List.of();
        if (order.getDomainMappingId() != null) {
            DomainMapping mapping = domainMappingRepository.findById(order.getDomainMappingId()).orElse(null);
            if (mapping != null && mapping.getDnsInstructionJson() != null) {
                instructions = readDns(mapping.getDnsInstructionJson());
                Object ns = instructions.get("intendedNameservers");
                if (ns instanceof List<?> list) {
                    intended = list.stream().map(String::valueOf).toList();
                }
            }
        }
        String businessName = null;
        String businessSlug = null;
        var biz = businessRepository.findByIdAndDeletedAtIsNull(order.getBusinessId());
        if (biz.isPresent()) {
            businessName = biz.get().getName();
            businessSlug = biz.get().getSlug();
        }
        return new DomainOrderResponse(
                order.getId(),
                order.getBusinessId(),
                businessName,
                businessSlug,
                order.getFqdn(),
                order.getStatus() == null ? null : order.getStatus().name().toLowerCase(Locale.ROOT),
                order.getNsStatus() == null ? null : order.getNsStatus().name().toLowerCase(Locale.ROOT),
                order.getPriceCents(),
                order.getCurrency(),
                order.getRegisterUrl(),
                order.getHostafricaDomainId(),
                order.isVercelZoneReady(),
                order.getDomainMappingId(),
                intended,
                instructions.isEmpty() ? null : instructions,
                order.getLastError(),
                merchantMessageFor(order),
                order.getPaidAt(),
                order.getPaymentCheckoutId(),
                order.getPaymentTxnId(),
                order.getPayerPhone(),
                order.getLastStkStatus(),
                paymentAvailableFor(order),
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }

    private String merchantMessageFor(DomainOrder order) {
        DomainOrderStatus status = order.getStatus();
        if (status == null) {
            return null;
        }
        return switch (status) {
            case QUOTED, AWAITING_PAYMENT -> {
                if (paymentAvailableFor(order)) {
                    if (GatewayStkPushStatuses.PENDING.equalsIgnoreCase(
                            order.getLastStkStatus() == null ? "" : order.getLastStkStatus())) {
                        yield "Waiting for M-Pesa confirmation on your phone.";
                    }
                    yield "Pay with M-Pesa to continue — we'll handle registration and DNS.";
                }
                yield "Payment is being confirmed. We'll start registration once it's cleared.";
            }
            case REGISTERING -> "We're registering your domain. This usually takes a few minutes.";
            case OWNED -> "Domain registered. Setting up DNS next…";
            case PROVISIONING -> {
                if (!order.isVercelZoneReady()) {
                    yield "Creating DNS for your shop…";
                }
                if (order.getNsStatus() != DomainNsStatus.ACTIVE) {
                    yield "Finishing DNS with the registrar — hang tight, no action needed from you.";
                }
                yield "Verifying SSL — almost live.";
            }
            case LIVE -> "Your shop is live on this domain.";
            case FAILED -> "Something went wrong setting up this domain. Contact support if it persists.";
        };
    }

    private boolean paymentAvailableFor(DomainOrder order) {
        if (order.getStatus() != DomainOrderStatus.AWAITING_PAYMENT
                && order.getStatus() != DomainOrderStatus.QUOTED) {
            return false;
        }
        return domainSettingsService.palmartStkConfigured();
    }

    private Map<String, Object> readDns(String raw) {
        try {
            return objectMapper.readValue(raw, new TypeReference<>() {});
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    private void requireBusiness(String businessId) {
        if (businessRepository.findByIdAndDeletedAtIsNull(businessId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found");
        }
    }

    private DomainOrder requireOrder(String businessId, String orderId) {
        return domainOrderRepository.findByIdAndBusinessIdAndDeletedAtIsNull(orderId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Domain order not found"));
    }

    private DomainOrder requireOrderAny(String orderId) {
        return domainOrderRepository.findById(orderId)
                .filter(o -> o.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Domain order not found"));
    }

    private void requireHostAfrica() {
        if (!hostAfricaClient.configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "HostAfrica is not configured");
        }
    }

    private String normalizeQuery(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Query is required");
        }
        String q = raw.trim().toLowerCase(Locale.ROOT);
        q = q.replaceAll("^https?://", "");
        q = q.replaceAll("/.*$", "");
        if (q.endsWith(".")) {
            q = q.substring(0, q.length() - 1);
        }
        return q;
    }

    private List<String> expandKenyanCandidates(String query) {
        if (query.contains(".")) {
            return List.of(query);
        }
        String label = query.replaceAll("[^a-z0-9-]", "");
        if (label.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid domain query");
        }
        return kenyanTlds().stream().map(tld -> label + "." + tld).toList();
    }

    private boolean isKenyanTld(HostAfricaClient.DomainQuote q) {
        return q.domain() != null && isKenyanTld(q.domain());
    }

    private boolean isKenyanTld(String fqdn) {
        String d = fqdn.toLowerCase(Locale.ROOT);
        for (String tld : kenyanTlds()) {
            if (d.equals(tld) || d.endsWith("." + tld)) {
                return true;
            }
        }
        return false;
    }

    private List<String> kenyanTlds() {
        String raw = domainSettingsService.resolve().hostafricaKenyanTlds();
        if (raw == null || raw.isBlank()) {
            return List.of("co.ke", "or.ke", "me.ke", "sc.ke", "ac.ke", "go.ke", "ke");
        }
        return List.of(raw.split(",")).stream()
                .map(String::trim)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
    }

    private static String stripTld(String query) {
        int i = query.indexOf('.');
        return i < 0 ? query : query.substring(0, i);
    }
}
