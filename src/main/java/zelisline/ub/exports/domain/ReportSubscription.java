package zelisline.ub.exports.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A recurring report-delivery schedule for one business. */
@Getter
@Setter
@Entity
@Table(name = "report_subscriptions")
public class ReportSubscription {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "report_key", nullable = false, length = 64)
    private String reportKey;

    @Column(name = "format", nullable = false, length = 16)
    private String format = "pdf";

    /** daily | weekly | monthly */
    @Column(name = "frequency", nullable = false, length = 16)
    private String frequency;

    /** HH:mm in the business timezone. */
    @Column(name = "send_time", nullable = false, length = 5)
    private String sendTime = "07:00";

    /** 1..7 (Mon..Sun) for weekly. */
    @Column(name = "day_of_week")
    private Integer dayOfWeek;

    /** 1..28 for monthly. */
    @Column(name = "day_of_month")
    private Integer dayOfMonth;

    /** CSV of whatsapp,sms,email. */
    @Column(name = "channels", nullable = false, length = 64)
    private String channels;

    @Column(name = "recipient_email", length = 255)
    private String recipientEmail;

    @Column(name = "branch_id", length = 36)
    private String branchId;

    @Column(name = "category_id", length = 36)
    private String categoryId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

    @Column(name = "last_sent_period", length = 16)
    private String lastSentPeriod;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
