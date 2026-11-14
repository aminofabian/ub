package zelisline.ub.serviceops.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/** A chair, bed, room or machine at a branch that one service uses at a time (ADR 0016). */
@Getter
@Setter
@Entity
@Table(
        name = "service_resources",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_service_resources_name", columnNames = {"business_id", "branch_id", "name"}))
public class ServiceResource {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "resource_type", nullable = false, length = 24)
    private String resourceType;

    /** The old chair number for resources made from the branch's chair count. */
    @Column(name = "station_no")
    private Integer stationNo;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "cleanup_minutes", nullable = false)
    private int cleanupMinutes;

    @Column(name = "blocked_until")
    private Instant blockedUntil;

    @Column(name = "blocked_reason", length = 191)
    private String blockedReason;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
