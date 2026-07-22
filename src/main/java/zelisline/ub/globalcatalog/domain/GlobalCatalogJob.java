package zelisline.ub.globalcatalog.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "global_catalog_jobs")
public class GlobalCatalogJob {

    public enum Kind {
        adopt,
        promote
    }

    public enum Status {
        pending,
        processing,
        completed,
        failed
    }

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status = Status.pending;

    /** Tenant business for adopt jobs; null for platform promote jobs. */
    @Column(name = "business_id", length = 36)
    private String businessId;

    @Column(name = "actor_user_id", nullable = false, length = 36)
    private String actorUserId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "json")
    private String payloadJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", columnDefinition = "json")
    private String resultJson;

    @Column(name = "rows_total")
    private Integer rowsTotal;

    @Column(name = "rows_processed", nullable = false)
    private int rowsProcessed;

    @Column(name = "rows_committed")
    private Integer rowsCommitted;

    @Column(name = "status_message", length = 1000)
    private String statusMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
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
