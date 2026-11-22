package zelisline.ub.wholesale.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Money a supplier's shop received against a sales invoice. */
@Getter
@Setter
@Entity
@Table(name = "sales_receipts")
public class SalesReceipt {

    public static final String CASH = "cash";
    public static final String MPESA = "mpesa";
    public static final String BANK = "bank";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "invoice_id", nullable = false, length = 36)
    private String invoiceId;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "method", nullable = false, length = 16)
    private String method;

    @Column(name = "reference", length = 128)
    private String reference;

    @Column(name = "received_on", nullable = false)
    private LocalDate receivedOn;

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
