package zelisline.ub.serviceops.domain;

import java.math.BigDecimal;
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

/**
 * The rate card for a category of lodging room — Standard Double, Executive (ADR 0030). A room may
 * belong to a type and inherit its nightly rate (weekday, and an optional weekend rate); a room's
 * own nightly_rate overrides the type for that one room. Business- and branch-scoped.
 */
@Getter
@Setter
@Entity
@Table(
        name = "room_types",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_room_types_name", columnNames = {"business_id", "branch_id", "name"}))
public class RoomType {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    /** What a weekday night costs (KES). */
    @Column(name = "default_nightly_rate", nullable = false, precision = 14, scale = 2)
    private BigDecimal defaultNightlyRate;

    /** What a weekend night (Friday or Saturday) costs, or null for the same as a weekday. */
    @Column(name = "weekend_nightly_rate", precision = 14, scale = 2)
    private BigDecimal weekendNightlyRate;

    /** How many the room sleeps, or null. */
    @Column(name = "capacity")
    private Integer capacity;

    /** Free text: "1 double bed", "2 singles". */
    @Column(name = "bed_config", length = 120)
    private String bedConfig;

    /** Free text: "Wi-Fi, TV, hot shower". */
    @Column(name = "amenities", length = 500)
    private String amenities;

    /** When a guest of this type checks in, or null to use the business default (ADR 0031). */
    @Column(name = "check_in_time")
    private java.time.LocalTime checkInTime;

    /** When a guest of this type checks out, or null to use the business default (ADR 0031). */
    @Column(name = "check_out_time")
    private java.time.LocalTime checkOutTime;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

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
