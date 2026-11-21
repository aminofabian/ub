package zelisline.ub.sales.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One physical opening of a till's cash drawer. {@code openedAt} is the
 * till's clock, not the time the server received the row.
 */
@Getter
@Setter
@Entity
@Table(name = "cash_drawer_opens")
public class CashDrawerOpen {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "shift_id", length = 36)
    private String shiftId;

    @Column(name = "sale_id", length = 36)
    private String saleId;

    @Column(name = "client_event_id", nullable = false, length = 36)
    private String clientEventId;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private CashDrawerOpenSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 16)
    private CashDrawerOpenReason reason;

    @Column(name = "sensor_confirmed", nullable = false)
    private boolean sensorConfirmed;

    @Column(name = "performed_by", length = 36)
    private String performedBy;

    @Column(name = "till_device_key", length = 64)
    private String tillDeviceKey;

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
