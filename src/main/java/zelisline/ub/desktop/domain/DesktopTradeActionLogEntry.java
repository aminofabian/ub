package zelisline.ub.desktop.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Cloud-side record that a till action has been applied, so a retried push returns the first
 * outcome instead of applying twice. See DESKTOP_SUPPLIER_TRADE_SYNC_SCOPE.md §S2.
 */
@Getter
@Setter
@Entity
@Table(name = "desktop_trade_action_log")
public class DesktopTradeActionLogEntry {

    @Id
    @Column(name = "action_id", nullable = false, length = 36)
    private String actionId;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "kind", nullable = false, length = 32)
    private String kind;

    @Column(name = "target_id", nullable = false, length = 36)
    private String targetId;

    @Column(name = "outcome", nullable = false, length = 16)
    private String outcome;

    @Column(name = "outcome_detail", length = 500)
    private String outcomeDetail;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

    @PrePersist
    void onCreate() {
        if (appliedAt == null) {
            appliedAt = Instant.now();
        }
    }
}
