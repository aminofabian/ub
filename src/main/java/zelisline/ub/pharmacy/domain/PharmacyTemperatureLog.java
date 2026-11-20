package zelisline.ub.pharmacy.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/** One storage temperature reading (a fridge, the shop floor). Append-only. */
@Getter
@Setter
@Entity
@Table(name = "pharmacy_temperature_logs")
public class PharmacyTemperatureLog {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "storage_name", nullable = false, length = 64)
    private String storageName;

    @Column(name = "reading_c", nullable = false, precision = 5, scale = 2)
    private BigDecimal readingC;

    /** The range the shop expects for this storage, as it stood when the reading was taken. */
    @Column(name = "min_c", nullable = false, precision = 5, scale = 2)
    private BigDecimal minC;

    @Column(name = "max_c", nullable = false, precision = 5, scale = 2)
    private BigDecimal maxC;

    @Column(name = "out_of_range", nullable = false)
    private boolean outOfRange;

    /** What was done about an out-of-range reading. */
    @Column(name = "action_note", length = 255)
    private String actionNote;

    @Column(name = "recorded_by", nullable = false, length = 36)
    private String recordedBy;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (recordedAt == null) {
            recordedAt = Instant.now();
        }
    }
}
