package zelisline.ub.sales.domain;

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
@Table(name = "shift_audit_logs")
public class ShiftAuditLog {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "shift_id", nullable = false, length = 36)
    private String shiftId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "performed_by", length = 36)
    private String performedBy;

    @Column(name = "metadata", length = 4000)
    private String metadata;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

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
