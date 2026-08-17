package zelisline.ub.payments.domain;

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

@Getter
@Setter
@Entity
@Table(name = "inbound_till_payments")
public class InboundTillPayment {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Enumerated(EnumType.STRING)
    @Column(name = "gateway_type", nullable = false, length = 32)
    private GatewayType gatewayType;

    @Column(name = "gateway_event_id", nullable = false, length = 128)
    private String gatewayEventId;

    @Column(name = "mpesa_receipt", length = 128)
    private String mpesaReceipt;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "payer_first_name", length = 120)
    private String payerFirstName;

    @Column(name = "payer_last_name", length = 120)
    private String payerLastName;

    @Column(name = "masked_msisdn", length = 32)
    private String maskedMsisdn;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "till_number", length = 64)
    private String tillNumber;

    @Column(name = "raw_payload", columnDefinition = "MEDIUMTEXT")
    private String rawPayload;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "linked_sale_id", length = 36)
    private String linkedSaleId;

    @Column(name = "linked_push_id", length = 36)
    private String linkedPushId;

    @Column(name = "linked_claim_id", length = 36)
    private String linkedClaimId;

    @Column(name = "linked_customer_id", length = 36)
    private String linkedCustomerId;

    @Column(name = "created_at", nullable = false)
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
        if (status == null || status.isBlank()) {
            status = InboundTillPaymentStatuses.PENDING;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
