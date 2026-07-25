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
 * Soft SMS/WhatsApp ping to the supplier after a Path B post with their public portal link.
 */
@Service
@RequiredArgsConstructor
public class SupplierPortalNotifyService {

    private static final Logger log = LoggerFactory.getLogger(SupplierPortalNotifyService.class);

    private final SupplierRepository supplierRepository;
    private final SupplierContactRepository supplierContactRepository;
    private final BusinessRepository businessRepository;
    private final DomainMappingRepository domainMappingRepository;
    private final SupplierPurchaseHistoryService purchaseHistoryService;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final CustomerMessageDispatcher customerMessageDispatcher;

    @Value("${app.public.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;

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

    public void notifySupplyPosted(
            String businessId,
            String supplierId,
            String invoiceNumber,
            BigDecimal grandTotal
    ) {
        try {
            Supplier supplier = supplierRepository
                    .findByIdAndBusinessIdAndDeletedAtIsNull(supplierId, businessId)
                    .orElse(null);
            if (supplier == null) {
                return;
            }
            String phoneDigits = resolvePhoneDigits(supplier);
            if (phoneDigits == null) {
                log.debug("Supplier portal SMS skipped — no phone for supplier {}", supplierId);
                return;
            }

            TenantMessagingConfig messaging = messagingSettingsService.resolveForTest(businessId);
            if (!messaging.enabled() || !messaging.smsConfigured()) {
                log.debug("Supplier portal SMS skipped — messaging not configured for {}", businessId);
                return;
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

            BigDecimal owed = BigDecimal.ZERO;
            try {
                owed = purchaseHistoryService
                        .purchaseHistory(businessId, supplierId, 1)
                        .summary()
                        .openBalance();
            } catch (Exception e) {
                log.debug("Could not load open balance for supplier SMS: {}", e.toString());
            }

            String total = money(grandTotal) + " " + currency;
            String owedStr = money(owed) + " " + currency;
            String inv = invoiceNumber != null && !invoiceNumber.isBlank()
                    ? invoiceNumber.trim()
                    : "supply";

            String body = shop + ": supply " + inv + " (" + total + ") received. "
                    + "Amount owed: " + owedStr + ". "
                    + "View history & note issues: " + portalUrl
                    + " — Payment within 48hrs.";

            var delivery = customerMessageDispatcher.deliverSmsOnly(messaging, phoneDigits, body);
            log.info(
                    "Supplier portal SMS supplier={} channel={} outcome={} detail={}",
                    supplierId,
                    delivery.channel(),
                    delivery.outcome(),
                    delivery.detail());
        } catch (Exception e) {
            log.warn("Supplier portal SMS failed soft: {}", e.toString());
        }
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

    private static String money(BigDecimal n) {
        if (n == null) {
            return "0.00";
        }
        return n.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
