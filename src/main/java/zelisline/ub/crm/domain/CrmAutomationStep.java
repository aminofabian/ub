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
 * One ordered step of a {@link CrmAutomation}. Steps run top-to-bottom in a single pass; each
 * action is best-effort and recorded on the run. M3.
 */
@Getter
@Setter
@Entity
@Table(name = "crm_automation_step")
public class CrmAutomationStep {

    /** Reply with {@code step_config.body}. */
    public static final String ACTION_SEND_MESSAGE = "SEND_MESSAGE";
    /** Add {@code step_config.tag} to the contact. */
    public static final String ACTION_ADD_TAG = "ADD_TAG";
    /** Assign the conversation to {@code step_config.userId} (blank unassigns). */
    public static final String ACTION_ASSIGN = "ASSIGN";
    /** Mark the conversation closed. */
    public static final String ACTION_CLOSE_CONVERSATION = "CLOSE_CONVERSATION";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "automation_id", nullable = false, length = 36)
    private String automationId;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "step_type", nullable = false, length = 40)
    private String stepType;

    /** Step configuration as JSON, e.g. {@code {"body":"..."}} or {@code {"tag":"vip"}}. */
    @Column(name = "step_config", columnDefinition = "MEDIUMTEXT")
    private String stepConfig;

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
