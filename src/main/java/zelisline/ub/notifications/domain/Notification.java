package zelisline.ub.notifications.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/** Phase 7 Slice 6 — in-app inbox row (`implement.md` §5.10). */
@Getter
@Setter
@Entity
@Table(
        name = "notifications",
        uniqueConstraints = @UniqueConstraint(name = "uq_notifications_business_dedupe", columnNames = {"business_id", "dedupe_key"}))
public class Notification {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "type", nullable = false, length = 64)
    private String type;

    @Column(name = "dedupe_key", length = 191)
    private String dedupeKey;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
