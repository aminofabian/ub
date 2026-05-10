package zelisline.ub.sales.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "shift_denominations")
public class ShiftDenomination {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "shift_id", nullable = false, length = 36)
    private String shiftId;

    @Column(name = "count_type", nullable = false, length = 10)
    private String countType;

    @Column(name = "denomination", nullable = false)
    private int denomination;

    @Column(name = "denomination_type", nullable = false, length = 10)
    private String denominationType;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "total", nullable = false, precision = 14, scale = 2)
    private BigDecimal total;

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
