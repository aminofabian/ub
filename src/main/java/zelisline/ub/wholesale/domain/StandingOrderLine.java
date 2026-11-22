package zelisline.ub.wholesale.domain;

import java.math.BigDecimal;
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
@Table(name = "standing_order_lines")
public class StandingOrderLine {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "standing_order_id", nullable = false, length = 36)
    private String standingOrderId;

    @Column(name = "item_id", nullable = false, length = 36)
    private String itemId;

    @Column(name = "item_name", nullable = false, length = 500)
    private String itemName;

    @Column(name = "quantity", nullable = false, precision = 14, scale = 4)
    private BigDecimal quantity;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
    }
}
