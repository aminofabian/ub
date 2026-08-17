package zelisline.ub.sales.domain;

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
@Table(name = "sale_items")
public class SaleItem {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "sale_id", nullable = false, length = 36)
    private String saleId;

    @Column(name = "line_index", nullable = false)
    private int lineIndex;

    @Column(name = "line_kind", nullable = false, length = 16)
    private String lineKind = SaleLineKinds.ITEM;

    @Column(name = "line_label", length = 255)
    private String lineLabel;

    @Column(name = "airtime_phone", length = 32)
    private String airtimePhone;

    @Column(name = "airtime_network", length = 16)
    private String airtimeNetwork;

    @Column(name = "item_id", length = 36)
    private String itemId;

    @Column(name = "batch_id", length = 36)
    private String batchId;

    @Column(name = "quantity", nullable = false, precision = 14, scale = 4)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "line_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal lineTotal;

    @Column(name = "unit_cost", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitCost;

    @Column(name = "cost_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal costTotal;

    @Column(name = "profit", nullable = false, precision = 14, scale = 2)
    private BigDecimal profit;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (lineKind == null || lineKind.isBlank()) {
            lineKind = SaleLineKinds.ITEM;
        }
    }

    public boolean isAirtime() {
        return SaleLineKinds.isAirtime(lineKind);
    }
}
