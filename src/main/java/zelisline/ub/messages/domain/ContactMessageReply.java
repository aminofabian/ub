package zelisline.ub.messages.domain;

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
@Table(name = "contact_message_replies")
public class ContactMessageReply {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "contact_message_id", nullable = false, length = 36)
    private String contactMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    private ContactReplyChannel channel;

    @Column(name = "body", nullable = false, length = 4000)
    private String body;

    @Column(name = "outcome", nullable = false, length = 32)
    private String outcome;

    @Column(name = "detail", length = 1000)
    private String detail;

    @Column(name = "sent_by_user_id", length = 36)
    private String sentByUserId;

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
