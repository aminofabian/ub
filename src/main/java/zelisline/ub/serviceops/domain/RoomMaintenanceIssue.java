package zelisline.ub.serviceops.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * A maintenance issue on a lodging room that outlives the housekeeping flag (ADR 0030 Phase 2). The
 * room's {@code MAINTENANCE} housekeeping status and its free-text note are wiped the moment the room
 * is turned CLEAN/INSPECTED, so a guest house keeps no record of what broke or that it has broken
 * before. This row is that record: a description, an open→in-progress→done lifecycle, an optional
 * photo (a stored URL) and assignee, and open/close times. Business- and branch-scoped.
 */
@Getter
@Setter
@Entity
@Table(name = "room_maintenance_issues")
public class RoomMaintenanceIssue {

    /** A newly raised issue, not yet being worked on. */
    public static final String STATUS_OPEN = "OPEN";
    /** Someone is on it. */
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    /** Fixed (or otherwise closed); kept as history. */
    public static final String STATUS_DONE = "DONE";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "room_resource_id", nullable = false, length = 36)
    private String roomResourceId;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** A photo of the fault, stored elsewhere and referenced by URL; null when none. */
    @Column(name = "photo_url", length = 1024)
    private String photoUrl;

    /** Who is fixing it (a staff_profiles id), or null. */
    @Column(name = "assigned_staff_profile_id", length = 36)
    private String assignedStaffProfileId;

    /** Who raised it (a user id), or null. */
    @Column(name = "opened_by", length = 36)
    private String openedBy;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    /** When it was marked DONE, or null while still open/in-progress. */
    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (openedAt == null) {
            openedAt = Instant.now();
        }
        if (status == null) {
            status = STATUS_OPEN;
        }
    }

    public boolean isClosed() {
        return STATUS_DONE.equals(status);
    }
}
