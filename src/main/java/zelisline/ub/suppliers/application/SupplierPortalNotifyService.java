package zelisline.ub.suppliers.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.messaging.application.CustomerMessageDispatcher;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierContact;
import zelisline.ub.suppliers.domain.SupplierSlug;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.DomainMapping;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Soft SMS ping to the supplier after a Path B post or supplier payment, with their public portal link.
 */
@Service
@RequiredArgsConstructor
public class SupplierPortalNotifyService {

    private static final Logger log = LoggerFactory.getLogger(SupplierPortalNotifyService.class);

    private final SupplierRepository supplierRepository;
    private final SupplierContactRepository supplierContactRepository;
    private final BusinessRepository businessRepository;
    private final DomainMappingRepository domainMappingRepository;
    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final SupplierPurchaseHistoryService purchaseHistoryService;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final CustomerMessageDispatcher customerMessageDispatcher;

    @Value("${app.public.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    @Value("${app.tenancy.platform-hosts:kiosk.ke}")
    private List<String> platformHosts;

    /**
     * Schedule a portal SMS after the current transaction commits (never blocks posting).
     */
    public void notifySupplyPostedAfterCommit(
            String businessId,
            String supplierId,
            String invoiceNumber,
            BigDecimal grandTotal
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            notifySupplyPosted(businessId, supplierId, invoiceNumber, grandTotal);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                notifySupplyPosted(businessId, supplierId, invoiceNumber, grandTotal);
            }
        });
    }

    /**
     * Schedule a payment confirmation SMS after the current transaction commits (never blocks payment).
     */
    public void notifySupplyPaidAfterCommit(
            String businessId,
            String supplierId,
            BigDecimal amountPaid,
            String paymentMethod,
            String reference,
            List<String> invoiceNumbers
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            notifySupplyPaid(businessId, supplierId, amountPaid, paymentMethod, reference, invoiceNumbers);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                notifySupplyPaid(businessId, supplierId, amountPaid, paymentMethod, reference, invoiceNumbers);
            }
        });
    }

    public void notifySupplyPosted(
            String businessId,
            String supplierId,
            String invoiceNumber,
            BigDecimal grandTotal
    ) {
        try {
            SupplierNotifyContext ctx = resolveNotifyContext(businessId, supplierId);
            if (ctx == null) {
                return;
            }

            BigDecimal owed = loadOpenBalance(businessId, supplierId);
            String total = money(grandTotal) + " " + ctx.currency();
            String owedStr = money(owed) + " " + ctx.currency();
            String inv = invoiceNumber != null && !invoiceNumber.isBlank()
                    ? invoiceNumber.trim()
                    : "supply";

            String body = ctx.shop() + ": supply " + inv + " (" + total + ") received. "
                    + "Amount owed: " + owedStr + ". "
                    + "View history & note issues: " + ctx.portalUrl();
            String globalUrl = resolveGlobalHubUrl(ctx.supplier());
            if (globalUrl != null) {
                body = body + " · All shops: " + globalUrl;
            }
            String claimUrl = buildClaimUrl(ctx.phoneDigits());
            if (claimUrl != null) {
                body = body + " · Claim account: " + claimUrl;
            }
            body = body + " — Payment within 48hrs.";

            deliverSupplierSms(ctx, body);
        } catch (Exception e) {
            log.warn("Supplier portal SMS failed soft: {}", e.toString());
        }
    }

    public void notifySupplyPaid(
            String businessId,
            String supplierId,
            BigDecimal amountPaid,
            String paymentMethod,
            String reference,
            List<String> invoiceNumbers
    ) {
        try {
            SupplierNotifyContext ctx = resolveNotifyContext(businessId, supplierId);
            if (ctx == null) {
                return;
            }

            BigDecimal owed = loadOpenBalance(businessId, supplierId);
            String body = buildPaidMessage(
                    ctx.shop(),
                    ctx.currency(),
                    amountPaid,
                    paymentMethod,
                    reference,
                    invoiceNumbers,
                    owed,
                    ctx.portalUrl());
            deliverSupplierSms(ctx, body);
        } catch (Exception e) {
            log.warn("Supplier payment SMS failed soft: {}", e.toString());
        }
    }

    static String buildPaidMessage(
            String shop,
            String currency,
            BigDecimal amountPaid,
            String paymentMethod,
            String reference,
            List<String> invoiceNumbers,
            BigDecimal openBalance,
            String portalUrl
    ) {
        String paid = money(amountPaid) + " " + currency;
        String owedStr = money(openBalance) + " " + currency;
        String supplies = formatInvoiceLabel(invoiceNumbers);
        String method = paymentMethod != null && !paymentMethod.isBlank()
                ? paymentMethod.trim().toLowerCase()
                : "cash";
        String ref = reference != null && !reference.isBlank() ? reference.trim() : null;

        StringBuilder body = new StringBuilder();
        body.append(shop).append(": paid ").append(paid)
                .append(" (").append(method).append(") for ").append(supplies).append(".");
        if (ref != null) {
            body.append(" Ref: ").append(ref).append(".");
        }
        body.append(" Balance owed: ").append(owedStr).append(".")
                .append(" View: ").append(portalUrl);
        return body.toString();
    }

    private SupplierNotifyContext resolveNotifyContext(String businessId, String supplierId) {
        // Soft-deleted suppliers may still be paid; allow SMS on payout phone / contacts.
        Supplier supplier = supplierRepository.findByIdAndBusinessId(supplierId, businessId).orElse(null);
        if (supplier == null) {
            return null;
        }
        String phoneDigits = resolvePhoneDigits(supplier);
        if (phoneDigits == null) {
            log.debug("Supplier SMS skipped — no phone for supplier {}", supplierId);
            return null;
        }

        TenantMessagingConfig messaging = messagingSettingsService.resolveForTest(businessId);
        if (!messaging.enabled() || !messaging.smsConfigured()) {
            log.debug("Supplier SMS skipped — messaging not configured for {}", businessId);
            return null;
        }

        Business business = businessRepository.findById(businessId).orElse(null);
        String shop = business != null && business.getName() != null
                ? business.getName().trim()
                : "Shop";
        String currency = business != null && business.getCurrency() != null
                ? business.getCurrency().trim()
                : "KES";
        String slug = SupplierSlug.canonical(supplier.getName(), supplier.getCode());
        String portalUrl = buildPortalUrl(businessId, slug);
        return new SupplierNotifyContext(supplier, phoneDigits, messaging, shop, currency, portalUrl);
    }

    private BigDecimal loadOpenBalance(String businessId, String supplierId) {
        try {
            return purchaseHistoryService
                    .purchaseHistory(businessId, supplierId, 1)
                    .summary()
                    .openBalance();
        } catch (Exception e) {
            log.debug("Could not load open balance for supplier SMS: {}", e.toString());
            return BigDecimal.ZERO;
        }
    }

    private void deliverSupplierSms(SupplierNotifyContext ctx, String body) {
        var delivery = customerMessageDispatcher.deliverSmsOnly(ctx.messaging(), ctx.phoneDigits(), body);
        log.info(
                "Supplier SMS supplier={} channel={} outcome={} detail={}",
                ctx.supplier().getId(),
                delivery.channel(),
                delivery.outcome(),
                delivery.detail());
    }

    private static String formatInvoiceLabel(List<String> invoiceNumbers) {
        if (invoiceNumbers == null || invoiceNumbers.isEmpty()) {
            return "supply";
        }
        List<String> cleaned = invoiceNumbers.stream()
                .filter(n -> n != null && !n.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (cleaned.isEmpty()) {
            return "supply";
        }
        if (cleaned.size() == 1) {
            return "supply " + cleaned.getFirst();
        }
        return "supplies " + String.join(", ", cleaned);
    }

    private record SupplierNotifyContext(
            Supplier supplier,
            String phoneDigits,
            TenantMessagingConfig messaging,
            String shop,
            String currency,
            String portalUrl
    ) {
    }

    private String resolvePhoneDigits(Supplier supplier) {
        String fromPayout = StkPhoneNormalizer.normalize(supplier.getPayoutPhone());
        if (fromPayout != null) {
            return fromPayout;
        }
        List<SupplierContact> contacts =
                supplierContactRepository.findBySupplierIdOrderByPrimaryContactDescNameAsc(
                        supplier.getId());
        for (SupplierContact c : contacts) {
            String n = StkPhoneNormalizer.normalize(c.getPhone());
            if (n != null) {
                return n;
            }
        }
        return null;
    }

    private String buildPortalUrl(String businessId, String slug) {
        String host = null;
        List<DomainMapping> domains =
                domainMappingRepository.findByBusinessIdAndDeletedAtIsNull(businessId);
        for (DomainMapping d : domains) {
            if (!d.isActive()) {
                continue;
            }
            if (d.isPrimary()) {
                host = d.getDomain();
                break;
            }
            if (host == null) {
                host = d.getDomain();
            }
        }
        if (host != null && !host.isBlank()) {
            String h = host.trim().toLowerCase();
            boolean local = h.endsWith(".localhost") || h.startsWith("localhost");
            String scheme = local ? "http://" : "https://";
            return scheme + h + "/s/" + slug;
        }
        String base = frontendBaseUrl == null ? "http://localhost:3000" : frontendBaseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/s/" + slug;
    }

    private String resolveGlobalHubUrl(Supplier supplier) {
        String mid = supplier.getMarketplaceSupplierId();
        if (mid == null || mid.isBlank()) {
            return null;
        }
        MarketplaceSupplier marketplace = marketplaceSupplierRepository.findById(mid).orElse(null);
        if (marketplace == null
                || marketplace.getUsername() == null
                || marketplace.getUsername().isBlank()) {
            return null;
        }
        String apex = resolvePlatformApexHost();
        boolean local = apex.endsWith(".localhost") || apex.startsWith("localhost");
        String scheme = local ? "http://" : "https://";
        return scheme + apex + "/s/" + marketplace.getUsername().trim();
    }

    private String buildClaimUrl(String phoneDigits) {
        if (phoneDigits == null || phoneDigits.isBlank()) {
            return null;
        }
        String apex = resolvePlatformApexHost();
        boolean local = apex.endsWith(".localhost") || apex.startsWith("localhost");
        String scheme = local ? "http://" : "https://";
        return scheme + apex + "/supplier-portal/claim?phone=" + phoneDigits;
    }

    private String resolvePlatformApexHost() {
        if (platformHosts != null) {
            for (String raw : platformHosts) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String h = raw.trim().toLowerCase();
                if (h.startsWith("www.")) {
                    h = h.substring(4);
                }
                if (h.equals("kiosk.ke") || h.equals("palmart.co.ke")) {
                    return h;
                }
            }
            for (String raw : platformHosts) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String h = raw.trim().toLowerCase();
                if (h.startsWith("www.")) {
                    h = h.substring(4);
                }
                // Skip API-only hosts (e.g. kiosk.zelisline.com).
                if (h.contains("zelisline") || h.startsWith("api.")) {
                    continue;
                }
                if (!h.isBlank()) {
                    return h;
                }
            }
        }
        return "kiosk.ke";
    }

    private static String money(BigDecimal n) {
        if (n == null) {
            return "0.00";
        }
        return n.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
