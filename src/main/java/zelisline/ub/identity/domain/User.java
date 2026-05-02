package zelisline.ub.identity.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * Tenant user (PHASE_1_PLAN.md §2.1).
 *
 * <p>Email uniqueness is per-tenant, not platform-wide. Either {@code passwordHash}
 * or {@code pinHash} (or both) must be present — enforced by a CHECK constraint
 * at the schema level.
 */
@Getter
@Setter
@Entity
@Table(
        name = "users",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_users_business_email",
                columnNames = {"business_id", "email"}
        ),
        indexes = {
                @Index(name = "idx_users_role",   columnList = "role_id"),
                @Index(name = "idx_users_branch", columnList = "branch_id"),
                @Index(name = "idx_users_status", columnList = "business_id, status")
        }
)
public class User {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", length = 36)
    private String branchId;

    @Column(name = "email", nullable = false, length = 191)
    private String email;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "pin_hash")
    private String pinHash;

    @Column(name = "status", nullable = false, length = 32)
    private String status = UserStatus.ACTIVE.wire();

    @Column(name = "role_id", nullable = false, length = 36)
    private String roleId;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    /** Start of the current 10-minute window for soft-lock counting (§3.4). */
    @Column(name = "auth_soft_window_start")
    private Instant authSoftWindowStart;

    /** Start of the current 1-hour window for hard-lock counting. */
    @Column(name = "auth_hour_window_start")
    private Instant authHourWindowStart;

    @Column(name = "auth_hour_failures", nullable = false)
    private int authHourFailures;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public UserStatus statusAsEnum() {
        return UserStatus.fromWire(status);
    }

    public void setStatus(UserStatus newStatus) {
        this.status = (newStatus == null ? UserStatus.ACTIVE : newStatus).wire();
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        email = normalizeEmail(email);
        if (status == null || status.isBlank()) {
            status = UserStatus.ACTIVE.wire();
        } else {
            status = status.trim().toLowerCase();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
        email = normalizeEmail(email);
        if (status != null) {
            status = status.trim().toLowerCase();
        }
    }

    private String normalizeEmail(String value) {
        return value == null ? null : value.trim().toLowerCase();
    }
}
