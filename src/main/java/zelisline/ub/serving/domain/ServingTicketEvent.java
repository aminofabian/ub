package zelisline.ub.serving.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "serving_ticket_events")
public class ServingTicketEvent {

    public static final String KIND_CREATED = "CREATED";
    public static final String KIND_ASSIGNED = "ASSIGNED";
    public static final String KIND_CLAIMED = "CLAIMED";
    public static final String KIND_STATUS = "STATUS";
    public static final String KIND_PRIORITY = "PRIORITY";
    public static final String KIND_NOTE = "NOTE";
    public static final String KIND_MESSAGE = "MESSAGE";
    public static final String KIND_PROMOTED = "PROMOTED";
    public static final String KIND_ESCALATED = "ESCALATED";
    public static final String KIND_ORGANIZED = "ORGANIZED";
    public static final String KIND_POINT = "POINT";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "ticket_id", nullable = false, length = 36)
    private String ticketId;

    @Column(name = "actor_id", length = 36)
    private String actorId;

    @Column(name = "actor_name", length = 191)
    private String actorName;

    @Column(name = "kind", nullable = false, length = 32)
    private String kind;

    @Column(name = "payload", length = 500)
    private String payload;

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
