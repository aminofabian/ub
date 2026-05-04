package zelisline.ub.exports.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Phase 7 Slice 6 — async-style export row (v1 processes synchronously after enqueue). */
@Getter
@Setter
@Entity
@Table(name = "export_jobs")
public class ExportJob {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "report_key", nullable = false, length = 64)
    private String reportKey;

    @Column(name = "format", nullable = false, length = 16)
    private String format;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "params_json", columnDefinition = "TEXT")
    private String paramsJson;

    @Column(name = "storage_path", length = 1024)
    private String storagePath;

    @Column(name = "download_token", length = 36)
    private String downloadToken;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "idempotency_key_hash", length = 64)
    private String idempotencyKeyHash;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

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
