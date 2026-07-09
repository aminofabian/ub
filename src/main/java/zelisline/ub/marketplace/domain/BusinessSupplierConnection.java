package zelisline.ub.marketplace.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "business_supplier_connections")
public class BusinessSupplierConnection {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "marketplace_supplier_id", nullable = false, length = 36)
    private String marketplaceSupplierId;

    @Column(name = "local_supplier_id", nullable = false, length = 36)
    private String localSupplierId;

    /** pending | active | suspended */
    @Column(name = "status", nullable = false, length = 16)
    private String status = BusinessSupplierConnectionStatuses.PENDING;

    @Column(name = "can_view_stock_levels", nullable = false)
    private boolean canViewStockLevels;

    @Column(name = "can_view_low_stock_alerts", nullable = false)
    private boolean canViewLowStockAlerts;

    @Column(name = "can_view_sales_velocity", nullable = false)
    private boolean canViewSalesVelocity;

    @Column(name = "can_view_demand_forecast", nullable = false)
    private boolean canViewDemandForecast;

    @Column(name = "can_suggest_restock", nullable = false)
    private boolean canSuggestRestock;

    @Column(name = "can_create_draft_po", nullable = false)
    private boolean canCreateDraftPo;

    @Column(name = "can_view_purchase_history", nullable = false)
    private boolean canViewPurchaseHistory = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
