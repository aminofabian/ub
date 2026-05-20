package zelisline.ub.payments.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "payment_webhook_events")
public class PaymentWebhookEvent {

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

    @Column(name = "topic", length = 128)
    private String topic;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Column(name = "raw_payload", columnDefinition = "MEDIUMTEXT")
    private String rawPayload;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (processedAt == null) {
            processedAt = Instant.now();
        }
    }
}
