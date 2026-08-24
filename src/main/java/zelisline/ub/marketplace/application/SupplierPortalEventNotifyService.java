package zelisline.ub.marketplace.application;

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
import zelisline.ub.marketplace.domain.SupplierPortalNotificationPref;
import zelisline.ub.marketplace.domain.SupplierUser;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.marketplace.repository.SupplierUserRepository;
import zelisline.ub.messaging.application.CustomerMessageDispatcher;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.notifications.SupplierPortalNotificationTypes;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.purchasing.domain.PurchaseOrderLine;
import zelisline.ub.purchasing.repository.PurchaseOrderLineRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * In-app (+ optional SMS) alerts for marketplace suppliers when shops take action.
 */
@Service
@RequiredArgsConstructor
public class SupplierPortalEventNotifyService {

    private static final Logger log = LoggerFactory.getLogger(SupplierPortalEventNotifyService.class);

    private final SupplierPortalNotificationsService notificationsService;
    private final SupplierUserRepository supplierUserRepository;
    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final BusinessRepository businessRepository;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final CustomerMessageDispatcher customerMessageDispatcher;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;

    @Value("${app.public.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    public void notifyPoSentAfterCommit(
            String businessId,
            String marketplaceSupplierId,
            String poNumber,
            String purchaseOrderId
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            notifyPoSent(businessId, marketplaceSupplierId, poNumber, purchaseOrderId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                notifyPoSent(businessId, marketplaceSupplierId, poNumber, purchaseOrderId);
            }
        });
    }

    public void notifyPaymentReceived(
            String businessId,
            String marketplaceSupplierId,
            java.math.BigDecimal amountPaid,
            String reference
    ) {
        try {
            String shopName = businessRepository.findById(businessId)
                    .map(Business::getName)
                    .orElse("A shop");
            String amount = amountPaid == null ? "" : amountPaid.toPlainString();
            String title = "Payment received";
            String body = shopName + " paid " + amount
                    + (reference != null && !reference.isBlank() ? " (ref " + reference.trim() + ")" : "")
                    + ".";
            List<SupplierUser> users = supplierUserRepository.findByMarketplaceSupplierIdAndActiveTrue(
                    marketplaceSupplierId);
            boolean anyInApp = users.isEmpty();
            for (SupplierUser user : users) {
                SupplierPortalNotificationPref prefs = notificationsService.loadOrDefault(
                        user.getId(), marketplaceSupplierId);
                if (prefs.isNotifyPaymentInApp()) {
                    anyInApp = true;
                }
            }
            if (anyInApp) {
                notificationsService.create(
                        marketplaceSupplierId,
                        SupplierPortalNotificationTypes.PAYMENT_RECEIVED,
                        title,
                        body,
                        "/supplier-portal/payments");
            }
        } catch (Exception ex) {
            log.warn("supplier portal payment notify failed msg={}", ex.getMessage());
        }
    }

    /** True when at least one active portal user wants payment SMS (default when unclaimed). */
    public boolean paymentSmsWanted(String marketplaceSupplierId) {
        List<SupplierUser> users = supplierUserRepository.findByMarketplaceSupplierIdAndActiveTrue(
                marketplaceSupplierId);
        if (users.isEmpty()) {
            return true;
        }
        return users.stream()
                .anyMatch(user -> notificationsService
                        .loadOrDefault(user.getId(), marketplaceSupplierId)
                        .isNotifyPaymentSms());
    }

    void notifyPoSent(
            String businessId,
            String marketplaceSupplierId,
            String poNumber,
            String purchaseOrderId
    ) {
        try {
            String shopName = businessRepository.findById(businessId)
                    .map(Business::getName)
                    .orElse("A shop");
            PoSummary summary = summarizePo(purchaseOrderId);
            String title = "Order received";
            String body = buildPoReceivedBody(shopName, poNumber, summary);
            String actionUrl = buildOrdersActionUrl(purchaseOrderId);

            List<SupplierUser> users = supplierUserRepository.findByMarketplaceSupplierIdAndActiveTrue(
                    marketplaceSupplierId);
            boolean anyInApp = users.isEmpty();
            boolean anySms = users.isEmpty();
            for (SupplierUser user : users) {
                SupplierPortalNotificationPref prefs = notificationsService.loadOrDefault(
                        user.getId(), marketplaceSupplierId);
                if (prefs.isNotifyPoInApp()) {
                    anyInApp = true;
                }
                if (prefs.isNotifyPoSms()) {
                    anySms = true;
                }
            }
            if (anyInApp) {
                notificationsService.create(
                        marketplaceSupplierId,
                        SupplierPortalNotificationTypes.PO_SENT,
                        title,
                        body,
                        actionUrl);
            }
            if (anySms) {
                sendPoSms(businessId, marketplaceSupplierId, shopName, poNumber, purchaseOrderId);
            }
        } catch (Exception ex) {
            log.warn("supplier portal PO notify failed poId={} msg={}", purchaseOrderId, ex.getMessage());
        }
    }

    private void sendPoSms(
            String businessId,
            String marketplaceSupplierId,
            String shopName,
            String poNumber,
            String purchaseOrderId
    ) {
        MarketplaceSupplier marketplace = marketplaceSupplierRepository.findById(marketplaceSupplierId).orElse(null);
        if (marketplace == null) {
            return;
        }
        String phone = StkPhoneNormalizer.normalize(marketplace.getContactPhone());
        if (phone == null) {
            List<SupplierUser> users = supplierUserRepository.findByMarketplaceSupplierIdAndActiveTrue(
                    marketplaceSupplierId);
            for (SupplierUser user : users) {
                phone = StkPhoneNormalizer.normalize(user.getPhone());
                if (phone != null) {
                    break;
                }
            }
        }
        if (phone == null) {
            return;
        }
        String portalUrl = trimSlash(frontendBaseUrl) + buildOrdersActionUrl(purchaseOrderId);
        String message = shopName + " sent purchase order " + poNumber
                + ". Open " + portalUrl + " to respond.";
        if (message.length() > 320) {
            message = message.substring(0, 317) + "...";
        }
        TenantMessagingConfig messaging = messagingSettingsService.resolveForTest(businessId);
        customerMessageDispatcher.deliver(messaging, phone, message);
    }

    static String buildOrdersActionUrl(String purchaseOrderId) {
        if (purchaseOrderId == null || purchaseOrderId.isBlank()) {
            return "/supplier-portal/orders";
        }
        return "/supplier-portal/orders?po=" + purchaseOrderId.trim();
    }

    static String buildPoReceivedBody(String shopName, String poNumber, PoSummary summary) {
        StringBuilder body = new StringBuilder();
        body.append(shopName == null || shopName.isBlank() ? "A shop" : shopName.trim());
        body.append(" sent ").append(poNumber == null ? "a purchase order" : poNumber.trim());
        if (summary != null && summary.lineCount() > 0) {
            body.append(" · ").append(summary.lineCount())
                    .append(summary.lineCount() == 1 ? " line" : " lines");
        }
        if (summary != null && summary.total() != null && summary.total().signum() > 0) {
            body.append(" · Ksh ").append(formatMoneyPlain(summary.total()));
        }
        body.append(".");
        return body.toString();
    }

    private PoSummary summarizePo(String purchaseOrderId) {
        if (purchaseOrderId == null || purchaseOrderId.isBlank()) {
            return PoSummary.empty();
        }
        try {
            List<PurchaseOrderLine> lines = purchaseOrderLineRepository
                    .findByPurchaseOrderIdOrderBySortOrderAscIdAsc(purchaseOrderId);
            if (lines.isEmpty()) {
                return PoSummary.empty();
            }
            BigDecimal total = BigDecimal.ZERO;
            for (PurchaseOrderLine line : lines) {
                if (line.getQtyOrdered() == null || line.getUnitEstimatedCost() == null) {
                    continue;
                }
                total = total.add(line.getQtyOrdered().multiply(line.getUnitEstimatedCost()));
            }
            return new PoSummary(lines.size(), total.setScale(2, RoundingMode.HALF_UP));
        } catch (Exception ex) {
            log.debug("PO summary skipped poId={} msg={}", purchaseOrderId, ex.getMessage());
            return PoSummary.empty();
        }
    }

    static String formatMoneyPlain(BigDecimal amount) {
        if (amount == null) {
            return "0";
        }
        return amount.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String trimSlash(String base) {
        if (base == null || base.isBlank()) {
            return "https://kiosk.ke";
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** Package-visible for unit tests around alert copy. */
    record PoSummary(int lineCount, BigDecimal total) {
        static PoSummary empty() {
            return new PoSummary(0, null);
        }
    }
}
