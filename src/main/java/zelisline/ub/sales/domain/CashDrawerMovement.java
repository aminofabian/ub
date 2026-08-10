package zelisline.ub.sales.domain;

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
 * One immutable per-denomination cash drawer movement within a shift.
 *
 * <p>Expected balances are derived by projecting these movements over the
 * opening count; the money sum of the projection must reconcile to
 * {@code shifts.expected_closing_cash}.
 */
@Getter
@Setter
@Entity
@Table(name = "cash_drawer_movements")
public class CashDrawerMovement {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "shift_id", nullable = false, length = 36)
    private String shiftId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "reference_id", nullable = false, length = 36)
    private String referenceId;

    @Column(name = "reference_type", nullable = false, length = 40)
    private String referenceType;

    @Column(name = "denomination", nullable = false)
    private int denomination;

    @Column(name = "denomination_type", nullable = false, length = 10)
    private String denominationType;

    @Column(name = "quantity_delta", nullable = false)
    private int quantityDelta;

    @Column(name = "confidence", nullable = false, length = 20)
    private String confidence;

    @Column(name = "performed_by", length = 36)
    private String performedBy;

    @Column(name = "metadata", length = 4000)
    private String metadata;

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
