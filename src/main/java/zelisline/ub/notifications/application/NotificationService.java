package zelisline.ub.notifications.application;

import java.time.Instant;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.notifications.domain.Notification;
import zelisline.ub.notifications.repository.NotificationRepository;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final NotificationDeliveryService deliveryService;

    @Transactional(readOnly = true)
    public List<Notification> list(String businessId) {
        return notificationRepository.findByBusinessIdOrderByCreatedAtDesc(businessId);
    }

    @Transactional(readOnly = true)
    public List<Notification> listForUser(String businessId, String userId, int limit) {
        int cap = Math.min(Math.max(limit, 1), 100);
        return notificationRepository.findByBusinessIdAndUserIdOrderByCreatedAtDesc(businessId, userId)
                .stream()
                .limit(cap)
                .toList();
    }

    @Transactional(readOnly = true)
    public long unreadCountForUser(String businessId, String userId) {
        return notificationRepository.countByBusinessIdAndUserIdAndReadAtIsNull(businessId, userId);
    }

    @Transactional
    public Notification markRead(String businessId, String notificationId) {
        Notification n = requireNotification(businessId, notificationId);
        n.setReadAt(Instant.now());
        return notificationRepository.save(n);
    }

    @Transactional
    public Notification markReadForUser(String businessId, String userId, String notificationId) {
        Notification n = requireNotification(businessId, notificationId);
        if (n.getUserId() == null || !n.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
        }
        n.setReadAt(Instant.now());
        return notificationRepository.save(n);
    }

    @Transactional
    public int markAllReadForUser(String businessId, String userId) {
        return notificationRepository.markAllReadForUser(businessId, userId, Instant.now());
    }

    /**
     * Idempotent insert for scanners — duplicates ignored via {@code dedupe_key} unique constraint.
     */
    @Transactional
    public java.util.Optional<Notification> tryInsertDedupe(
            String businessId,
            String type,
            String dedupeKey,
            String payloadJson
    ) {
        return tryInsertDedupe(businessId, type, dedupeKey, "operational", "MEDIUM", payloadJson);
    }

    @Transactional
    public java.util.Optional<Notification> tryInsertDedupe(
            String businessId,
            String type,
            String dedupeKey,
            String category,
            String priority,
            String payloadJson
    ) {
        return tryInsertDedupeForUser(businessId, null, type, dedupeKey, category, priority, payloadJson);
    }

    /**
     * Idempotent insert targeted at a single user (shopper in-app push).
     */
    @Transactional
    public java.util.Optional<Notification> tryInsertDedupeForUser(
            String businessId,
            String userId,
            String type,
            String dedupeKey,
            String payloadJson
    ) {
        return tryInsertDedupeForUser(businessId, userId, type, dedupeKey, "operational", "MEDIUM", payloadJson);
    }

    @Transactional
    public java.util.Optional<Notification> tryInsertDedupeForUser(
            String businessId,
            String userId,
            String type,
            String dedupeKey,
            String category,
            String priority,
            String payloadJson
    ) {
        if (notificationRepository.existsByBusinessIdAndDedupeKey(businessId, dedupeKey)) {
            return java.util.Optional.empty();
        }
        Notification n = new Notification();
        n.setBusinessId(businessId);
        n.setUserId(blankToNull(userId));
        n.setType(type);
        n.setCategory(blankToDefault(category, "operational"));
        n.setPriority(blankToDefault(priority, "MEDIUM"));
        n.setDedupeKey(dedupeKey);
        n.setPayloadJson(payloadJson);
        try {
            Notification saved = notificationRepository.save(n);
            deliveryService.recordDeliveriesForNotification(saved);
            eventPublisher.publishEvent(
                    new zelisline.ub.platform.realtime.RealtimeBridge.NotificationCreatedEvent(saved));
            return java.util.Optional.of(saved);
        } catch (DataIntegrityViolationException ex) {
            return java.util.Optional.empty();
        }
    }

    private Notification requireNotification(String businessId, String notificationId) {
        return notificationRepository.findById(notificationId)
                .filter(row -> businessId.equals(row.getBusinessId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found"));
    }

    private static String blankToNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    private static String blankToDefault(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return raw.trim();
    }
}
