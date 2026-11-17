package zelisline.ub.support.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One emoji reaction by one participant on one support-chat message. */
@Getter
@Setter
@Entity
@Table(name = "support_message_reactions")
public class SupportMessageReaction {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "message_id", nullable = false, length = 36)
    private String messageId;

    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;

    /** Tenant owner of the thread; null for platform (VISITOR) threads. */
    @Column(name = "business_id", length = 36)
    private String businessId;

    /** TENANT, SUPER_ADMIN, or GUEST. */
    @Column(name = "actor_type", nullable = false, length = 16)
    private String actorType;

    /** The staff userId or the guestId that owns this reaction. */
    @Column(name = "actor_ref", nullable = false, length = 64)
    private String actorRef;

    @Column(name = "emoji", nullable = false, length = 32)
    private String emoji;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

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
