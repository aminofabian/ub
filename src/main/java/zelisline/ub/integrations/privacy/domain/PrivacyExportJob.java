package zelisline.ub.integrations.privacy.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Phase 8 Slice 5 — portable ZIP of a data subject’s export (GDPR/DPA tooling). */
@Getter
@Setter
@Entity
@Table(name = "privacy_export_jobs")
public class PrivacyExportJob {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "subject_type", nullable = false, length = 16)
    private String subjectType;

    @Column(name = "subject_id", nullable = false, length = 36)
    private String subjectId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "storage_path", length = 1024)
    private String storagePath;

    @Column(name = "download_token", length = 36)
    private String downloadToken;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

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
