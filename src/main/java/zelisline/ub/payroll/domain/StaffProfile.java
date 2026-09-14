package zelisline.ub.payroll.domain;

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
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "staff_profiles")
public class StaffProfile {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    /** NOT NULL in MVP; may become nullable for non-login workers later. */
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

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
