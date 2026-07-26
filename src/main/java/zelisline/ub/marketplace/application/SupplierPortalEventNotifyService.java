package zelisline.ub.marketplace.application;

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
            String title = "New purchase order";
            String body = shopName + " sent PO " + poNumber + ".";
            String actionUrl = "/supplier-portal/orders";

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
                sendPoSms(businessId, marketplaceSupplierId, shopName, poNumber);
            }
        } catch (Exception ex) {
            log.warn("supplier portal PO notify failed poId={} msg={}", purchaseOrderId, ex.getMessage());
        }
    }

    private void sendPoSms(String businessId, String marketplaceSupplierId, String shopName, String poNumber) {
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
        String portalUrl = trimSlash(frontendBaseUrl) + "/supplier-portal/orders";
        String message = shopName + " sent purchase order " + poNumber
                + ". Open " + portalUrl + " to respond.";
        if (message.length() > 320) {
            message = message.substring(0, 317) + "...";
        }
        TenantMessagingConfig messaging = messagingSettingsService.resolveForTest(businessId);
        customerMessageDispatcher.deliver(messaging, phone, message);
    }

    private static String trimSlash(String base) {
        if (base == null || base.isBlank()) {
            return "https://kiosk.ke";
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
