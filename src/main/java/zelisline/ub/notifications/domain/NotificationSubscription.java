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

@Getter
@Setter
@Entity
@Table(
        name = "notification_subscriptions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_notification_subscriptions",
                columnNames = {"business_id", "user_id", "item_id", "kind"}))
public class NotificationSubscription {

    public static final String KIND_BACK_IN_STOCK = "BACK_IN_STOCK";
    public static final String KIND_PRICE_DROP = "PRICE_DROP";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "item_id", nullable = false, length = 36)
    private String itemId;

    @Column(name = "kind", nullable = false, length = 32)
    private String kind;

    @Column(name = "active", nullable = false)
    private boolean active = true;

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
