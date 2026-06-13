package zelisline.ub.posdraft.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "pos_draft_audit_log")
public class PosDraftAuditLog {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "draft_id", nullable = false, length = 36)
    private String draftId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "line_id", length = 36)
    private String lineId;

    @Column(name = "old_value", columnDefinition = "JSON")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "JSON")
    private String newValue;

    @Column(name = "created_at", nullable = false, updatable = false)
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
