package zelisline.ub.notifications.domain;

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
        name = "notification_preferences",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_notification_preferences",
                columnNames = {"business_id", "user_id", "category", "channel"}))
public class NotificationPreference {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "category", nullable = false, length = 32)
    private String category;

    @Column(name = "channel", nullable = false, length = 16)
    private String channel;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
    }
}
