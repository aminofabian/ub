package zelisline.ub.pharmacy.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A note that something on a dispensing record was written wrongly, and what is right. The record
 * itself is never edited; notes are only ever added.
 */
@Getter
@Setter
@Entity
@Table(name = "dispensing_record_corrections")
public class DispensingRecordCorrection {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "record_id", nullable = false, length = 36)
    private String recordId;

    @Column(name = "note", nullable = false, length = 1000)
    private String note;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

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
