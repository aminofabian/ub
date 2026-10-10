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
 * Raw inbound WhatsApp envelope, kept for idempotency (unique wamid) and replay.
 *
 * <p>{@code businessId} is null when the number is not routed yet — such rows are the
 * super-admin triage queue and never create conversations.
 */
@Getter
@Setter
@Entity
@Table(name = "crm_webhook_event")
public class CrmWebhookEvent {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    /** Meta wamid; unique so re-delivery is a no-op. */
    @Column(name = "wamid", length = 128)
    private String wamid;

    @Column(name = "phone_number_id", length = 64)
    private String phoneNumberId;

    /** Null = unrouted (no channel route): kept for super-admin triage. */
    @Column(name = "business_id", length = 36)
    private String businessId;

    @Column(name = "raw_json", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String rawJson;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
