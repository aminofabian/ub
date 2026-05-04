package zelisline.ub.credits.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Audit row written by the overdue reminder sweep. Unique on (credit_account_id, week_bucket) so
 * the sweep can be replayed safely; a dispatch failure can be detected by looking at {@code outcome}.
 */
@Getter
@Setter
@Entity
@Table(name = "credit_reminders")
public class CreditReminderRecord {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "credit_account_id", nullable = false, length = 36)
    private String creditAccountId;

    @Column(name = "week_bucket", nullable = false, length = 8)
    private String weekBucket;

    @Column(name = "channel", nullable = false, length = 16)
    private String channel;

    @Column(name = "outcome", nullable = false, length = 24)
    private String outcome;

    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (sentAt == null) {
            sentAt = Instant.now();
        }
    }
}
