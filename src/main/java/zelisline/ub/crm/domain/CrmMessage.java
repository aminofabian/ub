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

/** One inbound or outbound WhatsApp message within a conversation. */
@Getter
@Setter
@Entity
@Table(name = "crm_message")
public class CrmMessage {

    public static final String DIRECTION_INBOUND = "inbound";
    public static final String DIRECTION_OUTBOUND = "outbound";

    public static final String STATUS_RECEIVED = "received";
    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_SENT = "sent";
    public static final String STATUS_DELIVERED = "delivered";
    public static final String STATUS_READ = "read";
    public static final String STATUS_FAILED = "failed";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "direction", nullable = false, length = 8)
    private String direction;

    /** Meta wamid; unique when present so webhook re-delivery cannot double-insert. */
    @Column(name = "wa_message_id", length = 128)
    private String waMessageId;

    @Column(name = "type", nullable = false, length = 24)
    private String type;

    @Column(name = "body", columnDefinition = "MEDIUMTEXT")
    private String body;

    @Column(name = "content_json", columnDefinition = "MEDIUMTEXT")
    private String contentJson;

    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

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
