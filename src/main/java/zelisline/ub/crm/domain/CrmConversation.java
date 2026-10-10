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
 * One WhatsApp thread with a contact. {@code windowExpiresAt} tracks Meta's 24h
 * customer-service window (a template is required once it lapses).
 */
@Getter
@Setter
@Entity
@Table(name = "crm_conversation")
public class CrmConversation {

    public static final String STATUS_OPEN = "open";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_CLOSED = "closed";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "contact_id", nullable = false, length = 36)
    private String contactId;

    /**
     * Meta {@code phone_number_id} the customer messaged. Outbound replies leave from this number
     * when set, so a shop with its own number doesn't reply from the platform default. M4.
     */
    @Column(name = "phone_number_id", length = 64)
    private String phoneNumberId;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_OPEN;

    @Column(name = "assigned_user_id", length = 36)
    private String assignedUserId;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Column(name = "unread_count", nullable = false)
    private int unreadCount;

    /** Meta 24h service-window deadline; null until the first inbound. */
    @Column(name = "window_expires_at")
    private Instant windowExpiresAt;

    @Column(name = "ai_autoreply_disabled", nullable = false)
    private boolean aiAutoreplyDisabled;

    /** Auto-replies sent on this thread (cap enforced from {@code crm_ai_settings}). */
    @Column(name = "ai_reply_count", nullable = false)
    private int aiReplyCount;

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
