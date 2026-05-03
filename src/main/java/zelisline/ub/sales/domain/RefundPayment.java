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
@Table(name = "refund_payments")
public class RefundPayment {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "refund_id", nullable = false, length = 36)
    private String refundId;

    @Column(name = "method", nullable = false, length = 24)
    private String method;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "reference", length = 128)
    private String reference;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
    }
}
