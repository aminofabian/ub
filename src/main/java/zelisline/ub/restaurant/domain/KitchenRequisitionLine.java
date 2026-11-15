package zelisline.ub.restaurant.domain;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One product on a kitchen requisition: what was asked for and, once decided, what was given. */
@Getter
@Setter
@Entity
@Table(name = "kitchen_requisition_lines")
public class KitchenRequisitionLine {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "requisition_id", nullable = false, length = 36)
    private String requisitionId;

    @Column(name = "item_id", nullable = false, length = 36)
    private String itemId;

    /** The product's name when it was asked for, so the record still reads if it is renamed or removed. */
    @Column(name = "item_name", nullable = false, length = 255)
    private String itemName;

    @Column(name = "unit", length = 32)
    private String unit;

    @Column(name = "quantity_requested", nullable = false, precision = 14, scale = 4)
    private BigDecimal quantityRequested;

    /** Null until the requisition is issued; zero when this product was not given. */
    @Column(name = "quantity_issued", precision = 14, scale = 4)
    private BigDecimal quantityIssued;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
    }
}
