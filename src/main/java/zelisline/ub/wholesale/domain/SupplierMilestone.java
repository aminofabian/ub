package zelisline.ub.wholesale.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;

/** A milestone a supplier's shop reached, recorded once (SUPPLIER_ENGAGEMENT_DESIGN.md §5). */
@Getter
@Entity
@Table(name = "supplier_milestones")
public class SupplierMilestone {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "milestone_key", nullable = false, length = 64)
    private String milestoneKey;

    @Column(name = "achieved_at", nullable = false)
    private Instant achievedAt;

    /** When the supplier dismissed the note for this milestone. Null until then: the note is shown once. */
    @Column(name = "seen_at")
    private Instant seenAt;

    public void setSeenAt(Instant seenAt) {
        this.seenAt = seenAt;
    }

    protected SupplierMilestone() {
    }

    public SupplierMilestone(String businessId, String milestoneKey) {
        this.businessId = businessId;
        this.milestoneKey = milestoneKey;
    }

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (achievedAt == null) {
            achievedAt = Instant.now();
        }
    }
}
