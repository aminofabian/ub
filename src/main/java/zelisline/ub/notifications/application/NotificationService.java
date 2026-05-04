package zelisline.ub.notifications.application;

import java.time.Instant;
import java.util.List;

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

    @Transactional(readOnly = true)
    public List<Notification> list(String businessId) {
        return notificationRepository.findByBusinessIdOrderByCreatedAtDesc(businessId);
    }

    @Transactional
    public Notification markRead(String businessId, String notificationId) {
        Notification n = notificationRepository.findById(notificationId)
                .filter(row -> businessId.equals(row.getBusinessId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found"));
        n.setReadAt(Instant.now());
        return notificationRepository.save(n);
    }

    /**
     * Idempotent insert for scanners — duplicates ignored via {@code dedupe_key} unique constraint.
     *
     * @return persisted notification or {@code empty()} when duplicate.
     */
    @Transactional
    public java.util.Optional<Notification> tryInsertDedupe(
            String businessId,
            String type,
            String dedupeKey,
            String payloadJson
    ) {
        if (notificationRepository.existsByBusinessIdAndDedupeKey(businessId, dedupeKey)) {
            return java.util.Optional.empty();
        }
        Notification n = new Notification();
        n.setBusinessId(businessId);
        n.setType(type);
        n.setDedupeKey(dedupeKey);
        n.setPayloadJson(payloadJson);
        try {
            return java.util.Optional.of(notificationRepository.save(n));
        } catch (DataIntegrityViolationException ex) {
            return java.util.Optional.empty();
        }
    }
}
