package zelisline.ub.tenancy.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Purchase / provisioning order for a Kenyan TLD (HostAfrica → Vercel).
 * P0 creates the table; P1 wires HostAfrica checkout + billing.
 */
@Getter
@Setter
@Entity
@Table(name = "domain_orders")
public class DomainOrder {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "fqdn", nullable = false)
    private String fqdn;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DomainOrderStatus status = DomainOrderStatus.QUOTED;

    @Column(name = "hostafrica_domain_id", length = 64)
    private String hostafricaDomainId;

    @Lob
    @Column(name = "register_url", columnDefinition = "TEXT")
    private String registerUrl;

    @Column(name = "price_cents")
    private Long priceCents;

    @Column(name = "currency", length = 8)
    private String currency;

    @Column(name = "vercel_zone_ready", nullable = false)
    private boolean vercelZoneReady;

    @Enumerated(EnumType.STRING)
    @Column(name = "ns_status", nullable = false, length = 32)
    private DomainNsStatus nsStatus = DomainNsStatus.PENDING_OPS;

    @Column(name = "domain_mapping_id", length = 36)
    private String domainMappingId;

    @Lob
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "payment_checkout_id", length = 128)
    private String paymentCheckoutId;

    @Column(name = "payment_txn_id", length = 128)
    private String paymentTxnId;

    @Column(name = "payer_phone", length = 32)
    private String payerPhone;

    @Column(name = "last_stk_status", length = 32)
    private String lastStkStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

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
        if (fqdn != null) {
            fqdn = fqdn.trim().toLowerCase();
        }
        if (status == null) {
            status = DomainOrderStatus.QUOTED;
        }
        if (nsStatus == null) {
            nsStatus = DomainNsStatus.PENDING_OPS;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
        if (fqdn != null) {
            fqdn = fqdn.trim().toLowerCase();
        }
    }
}
