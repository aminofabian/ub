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
 * Append-only log of one firing of a {@link CrmAutomation}: which conversation it ran against,
 * the per-step outcome ({@code steps_json}), and the overall status. M3.
 */
@Getter
@Setter
@Entity
@Table(name = "crm_automation_run")
public class CrmAutomationRun {

    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_PARTIAL = "partial";
    public static final String STATUS_FAILED = "failed";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "automation_id", nullable = false, length = 36)
    private String automationId;

    @Column(name = "conversation_id", length = 36)
    private String conversationId;

    @Column(name = "contact_id", length = 36)
    private String contactId;

    @Column(name = "trigger_event", nullable = false, length = 60)
    private String triggerEvent;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** Per-step results as a JSON array: {@code [{"position":0,"type":"...","status":"ok"}]}. */
    @Column(name = "steps_json", columnDefinition = "MEDIUMTEXT")
    private String stepsJson;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

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
