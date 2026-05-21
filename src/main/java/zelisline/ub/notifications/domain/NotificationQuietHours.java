package zelisline.ub.notifications.domain;

import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "notification_quiet_hours")
public class NotificationQuietHours {

    @Id
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone = "Africa/Nairobi";

    @Column(name = "start_local", nullable = false)
    private LocalTime startLocal = LocalTime.of(22, 0);

    @Column(name = "end_local", nullable = false)
    private LocalTime endLocal = LocalTime.of(7, 0);

    @Column(name = "allow_high_priority", nullable = false)
    private boolean allowHighPriority = true;

    @Column(name = "promotional_enabled", nullable = false)
    private boolean promotionalEnabled = true;

    @Column(name = "max_promotional_per_day", nullable = false)
    private int maxPromotionalPerDay = 3;
}
