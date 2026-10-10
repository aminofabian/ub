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
 * Per-recipient ledger row for a {@link CrmBroadcast}. The status is the source of truth for the
 * broadcast's progress; Meta delivery/read callbacks are mirrored here by
 * {@code CrmDeliveryStatusService} via {@code wa_message_id}. M4.
 */
@Getter
@Setter
@Entity
@Table(name = "crm_broadcast_recipient")
public class CrmBroadcastRecipient {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SENT = "sent";
    public static final String STATUS_DELIVERED = "delivered";
    public static final String STATUS_READ = "read";
    public static final String STATUS_FAILED = "failed";
    /** Free-form broadcast to a contact whose 24h window had closed. */
    public static final String STATUS_SKIPPED = "skipped";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "broadcast_id", nullable = false, length = 36)
    private String broadcastId;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "contact_id", length = 36)
    private String contactId;

    @Column(name = "phone_e164", nullable = false, length = 20)
    private String phoneE164;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_PENDING;

    @Column(name = "wa_message_id", length = 128)
    private String waMessageId;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "read_at")
    private Instant readAt;

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
