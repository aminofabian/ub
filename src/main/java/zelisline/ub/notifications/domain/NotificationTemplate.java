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
        name = "notification_templates",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_notification_templates",
                columnNames = {"business_id", "type", "locale", "version"}))
public class NotificationTemplate {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", length = 36)
    private String businessId;

    @Column(name = "type", nullable = false, length = 64)
    private String type;

    @Column(name = "locale", nullable = false, length = 8)
    private String locale = "en";

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "title_template", nullable = false)
    private String titleTemplate;

    @Column(name = "body_template", nullable = false, columnDefinition = "TEXT")
    private String bodyTemplate;

    @Column(name = "action_url_template", length = 512)
    private String actionUrlTemplate;

    @Column(name = "notification_class", nullable = false, length = 16)
    private String notificationClass;

    @Column(name = "category", nullable = false, length = 32)
    private String category;

    @Column(name = "default_channels", nullable = false, columnDefinition = "JSON")
    private String defaultChannelsJson;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
    }
}
