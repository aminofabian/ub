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
 * One WhatsApp broadcast: a message (free-form text or an approved template) fanned out to a set
 * of contacts. Per-recipient state lives on {@link CrmBroadcastRecipient}; counts are derived from
 * those rows so they can never drift from the ledger. M4.
 */
@Getter
@Setter
@Entity
@Table(name = "crm_broadcast")
public class CrmBroadcast {

    /** Text body; only reaches contacts inside the 24h customer-service window. */
    public static final String MODE_FREE_FORM = "free_form";
    /** Approved template; can reach cold contacts. */
    public static final String MODE_TEMPLATE = "template";

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_SENDING = "sending";
    public static final String STATUS_SENT = "sent";
    public static final String STATUS_FAILED = "failed";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "mode", nullable = false, length = 16)
    private String mode;

    @Column(name = "body", columnDefinition = "MEDIUMTEXT")
    private String body;

    @Column(name = "template_name", length = 128)
    private String templateName;

    @Column(name = "template_language", length = 16)
    private String templateLanguage;

    /** Audience definition as JSON, e.g. {@code {"type":"all"}} or {@code {"type":"tag","tag":"vip"}}. */
    @Column(name = "audience_json", columnDefinition = "MEDIUMTEXT")
    private String audienceJson;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_DRAFT;

    /** Frozen recipient count at plan time. */
    @Column(name = "total_count", nullable = false)
    private int totalCount;

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
