package zelisline.ub.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "daily_stock_audit_items")
@IdClass(DailyStockAuditItemId.class)
public class DailyStockAuditItem {

    @Id
    @Column(name = "audit_id", nullable = false, length = 36)
    private String auditId;

    @Id
    @Column(name = "item_id", nullable = false, length = 36)
    private String itemId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
