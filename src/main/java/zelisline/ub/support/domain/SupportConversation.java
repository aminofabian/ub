package zelisline.ub.support.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One support thread per tenant, chatting with the platform (super-admin) team. */
@Getter
@Setter
@Entity
@Table(name = "support_conversations")
public class SupportConversation {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_RESOLVED = "RESOLVED";

    public static final String TYPE_TENANT = "TENANT";
    public static final String TYPE_VISITOR = "VISITOR";
    public static final String TYPE_STOREFRONT = "STOREFRONT";

    /** Synthetic business id for visitor threads on the platform site. */
    public static final String PLATFORM_BUSINESS = "platform";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "conversation_type", nullable = false, length = 16)
    private String conversationType = TYPE_TENANT;

    @Column(name = "guest_id", length = 64)
    private String guestId;

    @Column(name = "guest_name", length = 120)
    private String guestName;

    @Column(name = "guest_token_hash", length = 64)
    private String guestTokenHash;

    @Column(name = "guest_last_read_at")
    private Instant guestLastReadAt;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_OPEN;

    @Column(name = "subject", length = 191)
    private String subject;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

    @Column(name = "created_by_name", length = 191)
    private String createdByName;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Column(name = "last_message_preview", length = 500)
    private String lastMessagePreview;

    @Column(name = "tenant_last_read_at")
    private Instant tenantLastReadAt;

    @Column(name = "admin_last_read_at")
    private Instant adminLastReadAt;

    /** Only set for TENANT threads — keeps exactly one thread per business. */
    @Column(name = "tenant_thread_key", unique = true, length = 36)
    private String tenantThreadKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void touchLastMessage(Instant at, String preview) {
        lastMessageAt = at;
        lastMessagePreview = preview != null && preview.length() > 500
                ? preview.substring(0, 500)
                : preview;
    }
}
