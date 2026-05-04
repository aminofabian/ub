package zelisline.ub.integrations.csvimport.domain;

import java.time.Instant;

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
@Table(name = "import_jobs")
public class ImportJob {

    public enum Kind {
        items,
        suppliers,
        opening_stock
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

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status = Status.pending;

    @Column(name = "dry_run", nullable = false)
    private boolean dryRun;

    @Column(name = "actor_user_id", nullable = false, length = 36)
    private String actorUserId;

    @Column(name = "original_filename", length = 500)
    private String originalFilename;

    @Column(name = "payload_relative_path", nullable = false, length = 1024)
    private String payloadRelativePath;

    @Column(name = "rows_total")
    private Integer rowsTotal;

    @Column(name = "rows_processed", nullable = false)
    private int rowsProcessed;

    @Column(name = "rows_committed")
    private Integer rowsCommitted;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "errors_json")
    private String errorsJson;

    @Column(name = "status_message", length = 1000)
    private String statusMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
