package zelisline.ub.purchasing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import zelisline.ub.payments.domain.GatewayType;

@Getter
@Setter
@Entity
@Table(name = "supplier_disbursements")
public class SupplierDisbursement {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "supplier_id", nullable = false, length = 36)
    private String supplierId;

    @Column(name = "supplier_invoice_id", nullable = false, length = 36)
    private String supplierInvoiceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "gateway_type", nullable = false, length = 32)
    private GatewayType gatewayType;

    @Column(name = "payment_gateway_config_id", length = 36)
    private String paymentGatewayConfigId;

    @Column(name = "kopokopo_send_money_id", length = 128)
    private String kopokopoSendMoneyId;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency = "KES";

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @Column(name = "supplier_payment_id", length = 36)
    private String supplierPaymentId;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

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
        if (status == null || status.isBlank()) {
            status = SupplierDisbursementStatuses.PENDING;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
