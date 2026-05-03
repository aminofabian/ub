package zelisline.ub.finance.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "expense_schedule_occurrences")
public class ExpenseScheduleOccurrence {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "schedule_id", nullable = false, length = 36)
    private String scheduleId;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "occurrence_date", nullable = false)
    private LocalDate occurrenceDate;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "expense_id", length = 36)
    private String expenseId;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (status == null || status.isBlank()) {
            status = "posted";
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}

