package zelisline.ub.serviceops.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * A usual helper on a service and what they are paid for it, set once by the owner: "After shave,
 * 50 shillings", "Groomer, a quarter of what the barber earns" (ADR 0016, amendment 4). Either way
 * it is paid out of what the person leading the service earns, never out of the shop's part.
 */
@Getter
@Setter
@Entity
@Table(
        name = "service_helper_roles",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_service_helper_roles_name", columnNames = {"business_id", "name"}))
public class ServiceHelperRole {

    public static final String PAY_PERCENT = "PERCENT";
    public static final String PAY_AMOUNT = "AMOUNT";

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    /** {@link #PAY_PERCENT} of what the person leading earns, or a set {@link #PAY_AMOUNT} in shillings. */
    @Column(name = "pay_kind", nullable = false, length = 10)
    private String payKind;

    @Column(name = "pay_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal payValue;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

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

    /**
     * What this role is paid on a service where the person leading earns {@code leadEarns}, in
     * whole shillings. A percentage is rounded down, so the shares never exceed what is there.
     */
    public BigDecimal payFor(BigDecimal leadEarns) {
        if (PAY_AMOUNT.equals(payKind)) {
            return payValue.setScale(0, RoundingMode.HALF_UP);
        }
        return leadEarns.multiply(payValue).divide(HUNDRED, 0, RoundingMode.DOWN);
    }
}
