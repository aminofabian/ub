package zelisline.ub.purchasing.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "supplier_id", nullable = false, length = 36)
    private String supplierId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "po_number", nullable = false, length = 64)
    private String poNumber;

    @Column(name = "expected_date")
    private LocalDate expectedDate;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** manual | restock | marketplace */
    @Column(name = "source", nullable = false, length = 16)
    private String source = "manual";

    @Column(name = "sent_to_supplier_at")
    private Instant sentToSupplierAt;

    @Column(name = "supplier_response_at")
    private Instant supplierResponseAt;

    /** not_shipped | in_transit | delivered */
    @Column(name = "delivery_status", nullable = false, length = 16)
    private String deliveryStatus = "not_shipped";

    @Version
    @Column(name = "version", nullable = false)
    private long version;

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
