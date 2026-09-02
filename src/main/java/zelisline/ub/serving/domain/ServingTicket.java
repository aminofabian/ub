package zelisline.ub.serving.domain;

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

@Getter
@Setter
@Entity
@Table(name = "serving_tickets")
public class ServingTicket {

    public static final String TYPE_TENANT = "TENANT";
    public static final String TYPE_SHOPPER = "SHOPPER";

    public static final String STATUS_NEW = "NEW";
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String STATUS_CLOSED = "CLOSED";

    public static final String PRIORITY_LOW = "LOW";
    public static final String PRIORITY_NORMAL = "NORMAL";
    public static final String PRIORITY_HIGH = "HIGH";
    public static final String PRIORITY_URGENT = "URGENT";

    public static final String CATEGORY_BILLING = "BILLING";
    public static final String CATEGORY_ONBOARDING = "ONBOARDING";
    public static final String CATEGORY_BUG = "BUG";
    public static final String CATEGORY_DOMAIN = "DOMAIN";
    public static final String CATEGORY_MARKETPLACE = "MARKETPLACE";
    public static final String CATEGORY_OTHER = "OTHER";

    public static final String CREATED_SUPER_ADMIN = "SUPER_ADMIN";
    public static final String CREATED_TENANT = "TENANT";
    public static final String CREATED_SYSTEM = "SYSTEM";
    public static final String CREATED_GUEST = "GUEST";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "ticket_number", nullable = false, unique = true)
    private int ticketNumber;

    @Column(name = "type", nullable = false, length = 16)
    private String type = TYPE_TENANT;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_NEW;

    @Column(name = "priority", nullable = false, length = 16)
    private String priority = PRIORITY_NORMAL;

    @Column(name = "category", nullable = false, length = 32)
    private String category = CATEGORY_OTHER;

    @Column(name = "subject", nullable = false, length = 191)
    private String subject;

    @Column(name = "business_id", length = 36)
    private String businessId;

    @Column(name = "requester_user_id", length = 36)
    private String requesterUserId;

    @Column(name = "requester_name", length = 191)
    private String requesterName;

    @Column(name = "requester_email", length = 191)
    private String requesterEmail;

    @Column(name = "requester_phone", length = 32)
    private String requesterPhone;

    @Column(name = "shopper_guest_id", length = 64)
    private String shopperGuestId;

    @Column(name = "shopper_name", length = 120)
    private String shopperName;

    @Column(name = "shopper_phone", length = 32)
    private String shopperPhone;

    @Column(name = "shopper_user_id", length = 36)
    private String shopperUserId;

    @Column(name = "order_id", length = 36)
    private String orderId;

    @Column(name = "assigned_to", length = 36)
    private String assignedTo;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "conversation_id", length = 36)
    private String conversationId;

    @Column(name = "contact_message_id", length = 36)
    private String contactMessageId;

    @Column(name = "thread_from")
    private Instant threadFrom;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_by_kind", nullable = false, length = 16)
    private String createdByKind = CREATED_SYSTEM;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

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
        if (threadFrom == null) {
            threadFrom = createdAt;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String displayNumber() {
        return "K-" + ticketNumber;
    }

    public boolean isOpenWork() {
        return STATUS_NEW.equals(status)
                || STATUS_OPEN.equals(status)
                || STATUS_WAITING.equals(status);
    }
}
