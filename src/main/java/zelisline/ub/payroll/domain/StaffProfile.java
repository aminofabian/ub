package zelisline.ub.payroll.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
        name = "staff_profiles",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_staff_profiles_loginless_name",
                columnNames = {"business_id", "loginless_name_key"}))
public class StaffProfile {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    /** {@code null} for staff who never sign in (a barber with no account). Unique when set. */
    @Column(name = "user_id", length = 36)
    private String userId;

    /** Performs services and can be picked on a service sale line (ADR 0014). */
    @Column(name = "bookable", nullable = false)
    private boolean bookable;

    /**
     * Lower-cased display name while this is a current staff member with no login; {@code null}
     * otherwise. Unique per business, so two such people cannot share a name.
     */
    @Column(name = "loginless_name_key", length = 255)
    private String loginlessNameKey;

    /** The chair (station, room) this person usually works at, 1-based; {@code null} if none. */
    @Column(name = "usual_station_no")
    private Integer usualStationNo;

    /** How this person earns on services: {@code PERCENT}, {@code FIXED} or {@code null} for none. */
    @Column(name = "commission_type", length = 16)
    private String commissionType;

    /** Percent of the line (0–100) or KES per service, depending on {@link #commissionType}. */
    @Column(name = "commission_rate", precision = 14, scale = 2)
    private BigDecimal commissionRate;

    /** Percent earned on products this person sells; {@code null} or zero for none. */
    @Column(name = "product_commission_rate", precision = 5, scale = 2)
    private BigDecimal productCommissionRate;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "title", length = 128)
    private String title;

    @Column(name = "photo_url", length = 500)
    private String photoUrl;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "employment_status", nullable = false, length = 32)
    private String employmentStatus = EmploymentStatus.ACTIVE;

    /** When false, the person is omitted from payroll preview/pay-all (without terminating employment). */
    @Column(name = "include_in_payroll", nullable = false)
    private boolean includeInPayroll = true;

    /**
     * When true (legacy), mid-month joins were calendar-day prorated.
     * Prefer {@link #joinPayMode}.
     */
    @Column(name = "prorate_join_month", nullable = false)
    private boolean prorateJoinMonth = true;

    /**
     * How the join month is paid once salaries unlock on the 25th:
     * {@code full}, {@code half}, or {@code prorate}.
     */
    @Column(name = "join_pay_mode", nullable = false, length = 16)
    private String joinPayMode = JoinPayMode.HALF;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "national_id", length = 64)
    private String nationalId;

    @Column(name = "employee_code", length = 64)
    private String employeeCode;

    @Column(name = "emergency_contact_name", length = 255)
    private String emergencyContactName;

    @Column(name = "emergency_contact_phone", length = 50)
    private String emergencyContactPhone;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "bank_details", columnDefinition = "json")
    private String bankDetails;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (employmentStatus == null || employmentStatus.isBlank()) {
            employmentStatus = EmploymentStatus.ACTIVE;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
