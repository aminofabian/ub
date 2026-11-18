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

    /** For a restaurant table: how many it seats. */
    @Column(name = "capacity")
    private Integer capacity;

    /** For a restaurant table: the part of the floor it is in ("Terrace"). */
    @Column(name = "area", length = 60)
    private String area;

    /** For a restaurant table: the waiter who looks after it. A new order at the table is theirs unless another is named. */
    @Column(name = "assigned_staff_profile_id", length = 36)
    private String assignedStaffProfileId;

    /** For a restaurant table: the secret in its QR code. Null means customers cannot order from it. */
    @Column(name = "qr_token", length = 64, unique = true)
    private String qrToken;

    /** For a lodging room: what one night costs (KES). Null on every other kind of resource. */
    @Column(name = "nightly_rate", precision = 14, scale = 2)
    private java.math.BigDecimal nightlyRate;

    /**
     * For a lodging room: its housekeeping state, separate from occupancy and from {@link #active}
     * (ADR 0030). One of {@code CLEAN}, {@code DIRTY}, {@code CLEANING}, {@code INSPECTED},
     * {@code MAINTENANCE}. Null on every other kind of resource; a room with null reads as CLEAN.
     */
    @Column(name = "housekeeping_status", length = 16)
    private String housekeepingStatus;

    /**
     * For a lodging room: a short housekeeping note — damage found, an amenity missing (ADR 0030
     * Phase 2). Set by whoever turns the room round, cleared when it is dealt with. The cleaner
     * responsible reuses {@link #assignedStaffProfileId}.
     */
    @Column(name = "housekeeping_note", length = 300)
    private String housekeepingNote;

    /**
     * For a lodging room: the room type whose rate card it inherits (ADR 0030), or null for a room
     * priced only by its own {@link #nightlyRate}. Null on every non-room resource.
     */
    @Column(name = "room_type_id", length = 36)
    private String roomTypeId;

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
