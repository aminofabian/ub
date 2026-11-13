package zelisline.ub.messaging.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One tenant SMS that reached the send choke point ({@code SmsMessagingClient.sendText}).
 * The credit ledger counts spend; this row records who was sent what, so the shop can
 * see a customer's history, de-duplicate automatic texts and receive delivery reports
 * (SMS Hub, ADR 0013). Holds a phone number and message text: treat as PII.
 */
@Getter
@Setter
@Entity
@Table(name = "sms_messages")
public class SmsMessage {

    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_DELIVERED = "DELIVERED";
    public static final String STATUS_FAILED = "FAILED";
    /** Out of SMS credits: the provider was never called. */
    public static final String STATUS_BLOCKED = "BLOCKED";

    public static final String CLASS_TRANSACTIONAL = "TRANSACTIONAL";
    public static final String CLASS_PROMOTIONAL = "PROMOTIONAL";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "customer_id", length = 36)
    private String customerId;

    /** Digits only, international form (2547…). */
    @Column(name = "phone", nullable = false, length = 32)
    private String phone;

    @Column(name = "body", nullable = false, length = 1600)
    private String body;

    @Column(name = "parts", nullable = false)
    private int parts = 1;

    @Column(name = "credits", nullable = false)
    private int credits;

    @Column(name = "message_class", nullable = false, length = 16)
    private String messageClass = CLASS_TRANSACTIONAL;

    @Column(name = "reason", length = 64)
    private String reason;

    @Column(name = "reference_id", length = 128)
    private String referenceId;

    @Column(name = "campaign_id", length = 36)
    private String campaignId;

    @Column(name = "provider", length = 32)
    private String provider;

    @Column(name = "provider_message_id", length = 128)
    private String providerMessageId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "error", length = 500)
    private String error;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

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
