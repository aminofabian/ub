package zelisline.ub.audit.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Immutable audit event row. Every business-meaningful mutation or security-relevant
 * observation in the POS/e-commerce platform is recorded here.
 *
 * <p>The table is append-only: there is no {@code updated_at} or {@code deleted_at}.
 */
@Getter
@Setter
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", length = 36)
    private String branchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 40)
    private AuditEventCategory category;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private AuditEventSeverity severity;

    @Column(name = "actor_id", length = 36)
    private String actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    private AuditEventActorType actorType;

    @Column(name = "actor_name", length = 255)
    private String actorName;

    @Column(name = "target_type", length = 60)
    private String targetType;

    @Column(name = "target_id", length = 36)
    private String targetId;

    @Column(name = "target_label", length = 255)
    private String targetLabel;

    @Column(name = "session_id", length = 36)
    private String sessionId;

    @Column(name = "correlation_id", length = 36)
    private String correlationId;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "source", length = 40)
    private String source;

    @Column(name = "terminal_id", length = 80)
    private String terminalId;

    @Column(name = "shift_id", length = 36)
    private String shiftId;

    @Column(name = "old_state", columnDefinition = "JSON")
    private String oldState;

    @Column(name = "new_state", columnDefinition = "JSON")
    private String newState;

    @Column(name = "diff", columnDefinition = "JSON")
    private String diff;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "metadata", columnDefinition = "JSON")
    private String metadata;

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
