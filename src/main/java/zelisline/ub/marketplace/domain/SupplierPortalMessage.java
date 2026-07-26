package zelisline.ub.marketplace.domain;

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
@Table(name = "supplier_portal_messages")
public class SupplierPortalMessage {

    public static final String FROM_SHOP = "from_shop";
    public static final String FROM_SUPPLIER = "from_supplier";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "marketplace_supplier_id", nullable = false, length = 36)
    private String marketplaceSupplierId;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "local_supplier_id", length = 36)
    private String localSupplierId;

    @Column(name = "direction", nullable = false, length = 16)
    private String direction;

    @Column(name = "author_name", nullable = false, length = 120)
    private String authorName;

    @Column(name = "body", nullable = false, length = 4000)
    private String body;

    @Column(name = "contact_message_id", length = 36)
    private String contactMessageId;

    @Column(name = "read_at")
    private Instant readAt;

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
