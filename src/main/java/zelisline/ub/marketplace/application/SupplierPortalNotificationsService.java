package zelisline.ub.marketplace.application;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.PatchSupplierPortalNotificationPrefsRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalNotificationPrefsResponse;
import zelisline.ub.marketplace.api.dto.SupplierPortalNotificationRow;
import zelisline.ub.marketplace.domain.SupplierPortalNotification;
import zelisline.ub.marketplace.domain.SupplierPortalNotificationPref;
import zelisline.ub.marketplace.repository.SupplierPortalNotificationPrefRepository;
import zelisline.ub.marketplace.repository.SupplierPortalNotificationRepository;

@Service
@RequiredArgsConstructor
public class SupplierPortalNotificationsService {

    private final SupplierPortalNotificationRepository notificationRepository;
    private final SupplierPortalNotificationPrefRepository prefRepository;

    @Transactional(readOnly = true)
    public List<SupplierPortalNotificationRow> list(String marketplaceSupplierId) {
        return notificationRepository.findByMarketplaceSupplierIdOrderByCreatedAtDesc(marketplaceSupplierId)
                .stream()
                .map(this::toRow)
                .toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(String marketplaceSupplierId) {
        return notificationRepository.countByMarketplaceSupplierIdAndReadAtIsNull(marketplaceSupplierId);
    }

    @Transactional
    public void markRead(String marketplaceSupplierId, String notificationId) {
        SupplierPortalNotification row = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found"));
        if (!marketplaceSupplierId.equals(row.getMarketplaceSupplierId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
        }
        if (row.getReadAt() == null) {
            row.setReadAt(java.time.Instant.now());
            notificationRepository.save(row);
        }
    }

    @Transactional
    public void markAllRead(String marketplaceSupplierId) {
        List<SupplierPortalNotification> unread = notificationRepository
                .findByMarketplaceSupplierIdOrderByCreatedAtDesc(marketplaceSupplierId)
                .stream()
                .filter(n -> n.getReadAt() == null)
                .toList();
        java.time.Instant now = java.time.Instant.now();
        for (SupplierPortalNotification n : unread) {
            n.setReadAt(now);
        }
        notificationRepository.saveAll(unread);
    }

    @Transactional
    public void create(
            String marketplaceSupplierId,
            String type,
            String title,
            String body,
            String actionUrl
    ) {
        SupplierPortalNotification n = new SupplierPortalNotification();
        n.setMarketplaceSupplierId(marketplaceSupplierId);
        n.setType(type);
        n.setTitle(title);
        n.setBody(body);
        n.setActionUrl(actionUrl);
        notificationRepository.save(n);
    }

    @Transactional(readOnly = true)
    public SupplierPortalNotificationPrefsResponse getPrefs(String supplierUserId, String marketplaceSupplierId) {
        return toPrefs(loadOrDefault(supplierUserId, marketplaceSupplierId));
    }

    @Transactional
    public SupplierPortalNotificationPrefsResponse patchPrefs(
            String supplierUserId,
            String marketplaceSupplierId,
            PatchSupplierPortalNotificationPrefsRequest body
    ) {
        SupplierPortalNotificationPref pref = loadOrDefault(supplierUserId, marketplaceSupplierId);
        if (body.notifyPoInApp() != null) {
            pref.setNotifyPoInApp(body.notifyPoInApp());
        }
        if (body.notifyPoSms() != null) {
            pref.setNotifyPoSms(body.notifyPoSms());
        }
        if (body.notifyPaymentInApp() != null) {
            pref.setNotifyPaymentInApp(body.notifyPaymentInApp());
        }
        if (body.notifyPaymentSms() != null) {
            pref.setNotifyPaymentSms(body.notifyPaymentSms());
        }
        if (body.notifyDeliveryInApp() != null) {
            pref.setNotifyDeliveryInApp(body.notifyDeliveryInApp());
        }
        prefRepository.save(pref);
        return toPrefs(pref);
    }

    @Transactional(readOnly = true)
    public SupplierPortalNotificationPref loadOrDefault(String supplierUserId, String marketplaceSupplierId) {
        return prefRepository.findBySupplierUserId(supplierUserId).orElseGet(() -> {
            SupplierPortalNotificationPref pref = new SupplierPortalNotificationPref();
            pref.setSupplierUserId(supplierUserId);
            pref.setMarketplaceSupplierId(marketplaceSupplierId);
            return pref;
        });
    }

    private SupplierPortalNotificationRow toRow(SupplierPortalNotification n) {
        return new SupplierPortalNotificationRow(
                n.getId(),
                n.getType(),
                n.getTitle(),
                n.getBody(),
                n.getActionUrl(),
                n.getCreatedAt(),
                n.getReadAt());
    }

    private static SupplierPortalNotificationPrefsResponse toPrefs(SupplierPortalNotificationPref pref) {
        return new SupplierPortalNotificationPrefsResponse(
                pref.isNotifyPoInApp(),
                pref.isNotifyPoSms(),
                pref.isNotifyPaymentInApp(),
                pref.isNotifyPaymentSms(),
                pref.isNotifyDeliveryInApp());
    }
}
