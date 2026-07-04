package zelisline.ub.inventory.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "daily_stock_audits")
public class DailyStockAudit {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "audit_date", nullable = false)
    private LocalDate auditDate;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "generated_by", nullable = false, length = 36)
    private String generatedBy;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "audit_id")
    @OrderBy("sortOrder ASC, itemId ASC")
    private List<DailyStockAuditItem> items = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (generatedAt == null) {
            generatedAt = Instant.now();
        }
    }

    public void addItem(String itemId, int sortOrder) {
        DailyStockAuditItem row = new DailyStockAuditItem();
        row.setAuditId(id);
        row.setItemId(itemId);
        row.setSortOrder(sortOrder);
        items.add(row);
    }
}
