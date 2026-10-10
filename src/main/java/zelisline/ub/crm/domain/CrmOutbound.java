package zelisline.ub.crm.domain;

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
 * Outbound send outbox: enqueued in the caller's transaction and drained by a scheduler
 * (shape mirrors {@code integrations/webhook WebhookDelivery}) so a send never blocks the
 * POS path.
 */
@Getter
@Setter
@Entity
@Table(name = "crm_outbound")
public class CrmOutbound {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SENDING = "sending";
    public static final String STATUS_SENT = "sent";
    public static final String STATUS_FAILED = "failed";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "conversation_id", length = 36)
    private String conversationId;

    @Column(name = "to_phone_e164", nullable = false, length = 20)
    private String toPhoneE164;

    @Column(name = "payload_json", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String payloadJson;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    /** Set for broadcast sends: links the outbox row back to its recipient ledger row (M4). */
    @Column(name = "broadcast_recipient_id", length = 36)
    private String broadcastRecipientId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }
}
