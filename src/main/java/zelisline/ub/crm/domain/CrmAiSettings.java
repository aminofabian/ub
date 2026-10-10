package zelisline.ub.crm.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Per-business AI/auto-reply settings. {@code businessId} is the primary key. */
@Getter
@Setter
@Entity
@Table(name = "crm_ai_settings")
public class CrmAiSettings {

    @Id
    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "auto_reply_enabled", nullable = false)
    private boolean autoReplyEnabled;

    @Column(name = "max_replies_per_conversation", nullable = false)
    private int maxRepliesPerConversation = 3;

    /** Agent to route to on handoff; null leaves the thread in the shared queue. */
    @Column(name = "handoff_user_id", length = 36)
    private String handoffUserId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
