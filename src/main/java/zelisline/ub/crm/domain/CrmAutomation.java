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
 * A no-code automation rule for the WhatsApp inbox: a trigger plus an ordered list of steps
 * ({@link CrmAutomationStep}). Each firing is recorded as a {@link CrmAutomationRun}.
 *
 * <p>M3; see {@code docs/scopes/whatsapp-crm/SCOPE.md} §6.3 and §9.
 */
@Getter
@Setter
@Entity
@Table(name = "crm_automation")
public class CrmAutomation {

    /** Inbound text contains one of {@code trigger_config.keywords}. */
    public static final String TRIGGER_KEYWORD = "KEYWORD_MATCH";
    /** The contact's first-ever inbound message on this shop's WhatsApp. */
    public static final String TRIGGER_FIRST_INBOUND = "FIRST_INBOUND_MESSAGE";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "trigger_type", nullable = false, length = 40)
    private String triggerType;

    /** Trigger configuration as JSON, e.g. {@code {"keywords":["price"]}}. */
    @Column(name = "trigger_config", columnDefinition = "MEDIUMTEXT")
    private String triggerConfig;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "execution_count", nullable = false)
    private int executionCount;

    @Column(name = "last_executed_at")
    private Instant lastExecutedAt;

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
