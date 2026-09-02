package zelisline.ub.serving.domain;

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

@Getter
@Setter
@Entity
@Table(name = "serving_ticket_points")
public class ServingTicketPoint {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_DONE = "DONE";

    public static final String SOURCE_AI = "AI";
    public static final String SOURCE_HEURISTIC = "HEURISTIC";
    public static final String SOURCE_STAFF = "STAFF";

    public static final String COMPLETED_STAFF = "STAFF";
    public static final String COMPLETED_TENANT = "TENANT";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "ticket_id", nullable = false, length = 36)
    private String ticketId;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "title", nullable = false, length = 191)
    private String title;

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_OPEN;

    @Column(name = "source", nullable = false, length = 16)
    private String source = SOURCE_STAFF;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "completed_by", length = 36)
    private String completedBy;

    @Column(name = "completed_by_name", length = 191)
    private String completedByName;

    @Column(name = "completed_by_kind", length = 16)
    private String completedByKind;

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
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null || status.isBlank()) {
            status = STATUS_OPEN;
        }
        if (source == null || source.isBlank()) {
            source = SOURCE_STAFF;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isDone() {
        return STATUS_DONE.equals(status);
    }
}
