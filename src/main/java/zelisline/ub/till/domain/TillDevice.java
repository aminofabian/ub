package zelisline.ub.till.domain;

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
@Table(name = "till_devices")
public class TillDevice {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "device_key", nullable = false, length = 64)
    private String deviceKey;

    @Column(name = "label", nullable = false, length = 80)
    private String label;

    @Column(name = "registered_by", nullable = false, length = 36)
    private String registeredBy;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        Instant now = Instant.now();
        if (registeredAt == null) {
            registeredAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
