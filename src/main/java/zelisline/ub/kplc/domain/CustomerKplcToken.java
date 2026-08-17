package zelisline.ub.kplc.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
@Table(name = "customer_kplc_tokens")
public class CustomerKplcToken {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "customer_id", nullable = false, length = 36)
    private String customerId;

    @Column(name = "meter_number", nullable = false, length = 16)
    private String meterNumber;

    @Column(name = "token_no", nullable = false, length = 32)
    private String tokenNo;

    @Column(name = "purchased_at")
    private Instant purchasedAt;

    @Column(name = "amount", precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "units", precision = 12, scale = 4)
    private BigDecimal units;

    @Column(name = "receipt_no", length = 64)
    private String receiptNo;

    @Column(name = "payment_method", length = 32)
    private String paymentMethod;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "concepts_json")
    private String conceptsJson;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (firstSeenAt == null) {
            firstSeenAt = now;
        }
        if (lastSeenAt == null) {
            lastSeenAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        lastSeenAt = Instant.now();
    }
}
