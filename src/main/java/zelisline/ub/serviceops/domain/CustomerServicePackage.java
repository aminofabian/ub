package zelisline.ub.serviceops.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * Sessions of a service a customer has paid for in advance and not yet used (ADR 0015). The
 * money sits in {@code DEFERRED_SERVICE_REVENUE} until each session is used.
 */
@Getter
@Setter
@Entity
@Table(
        name = "customer_service_packages",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_customer_service_packages_key", columnNames = {"business_id", "idempotency_key"}))
public class CustomerServicePackage {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "customer_id", nullable = false, length = 36)
    private String customerId;

    @Column(name = "service_id", nullable = false, length = 36)
    private String serviceId;

    /** The service name when the package was sold. */
    @Column(name = "service_name", nullable = false, length = 191)
    private String serviceName;

    @Column(name = "sessions_total", nullable = false)
    private int sessionsTotal;

    @Column(name = "sessions_remaining", nullable = false)
    private int sessionsRemaining;

    @Column(name = "price_paid", nullable = false, precision = 14, scale = 2)
    private BigDecimal pricePaid;

    /** What is still held for the unused sessions; reaches zero with the last one. */
    @Column(name = "value_remaining", nullable = false, precision = 14, scale = 2)
    private BigDecimal valueRemaining;

    @Column(name = "payment_method", nullable = false, length = 32)
    private String paymentMethod;

    @Column(name = "reference", length = 64)
    private String reference;

    @Column(name = "sold_by", nullable = false, length = 36)
    private String soldBy;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "idempotency_key", nullable = false, length = 80)
    private String idempotencyKey;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
