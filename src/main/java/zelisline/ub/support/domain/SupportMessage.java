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

/** One chat message inside a support conversation. */
@Getter
@Setter
@Entity
@Table(name = "support_messages")
public class SupportMessage {

    public static final String SENDER_TENANT = "TENANT";
    public static final String SENDER_SUPER_ADMIN = "SUPER_ADMIN";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;

    @Column(name = "sender_type", nullable = false, length = 16)
    private String senderType;

    @Column(name = "sender_user_id", nullable = false, length = 36)
    private String senderUserId;

    @Column(name = "sender_name", length = 191)
    private String senderName;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "read_at")
    private Instant readAt;

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
