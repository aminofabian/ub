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
 * One thing a supplier did on the desktop till (answered, dispatched or cancelled an order; later:
 * approved an account, recorded a payment). The till queues these and pushes them; the cloud applies
 * each once, by id, through the same service the web calls. See DESKTOP_SUPPLIER_TRADE_SYNC_SCOPE.md.
 */
@Getter
@Setter
@Entity
@Table(name = "desktop_trade_actions")
public class DesktopTradeAction {

    public static final String KIND_ANSWER = "answer";
    public static final String KIND_DISPATCH = "dispatch";
    public static final String KIND_CANCEL = "cancel";
    public static final String KIND_APPROVE_ACCOUNT = "approve_account";
    public static final String KIND_RECORD_PAYMENT = "record_payment";

    public static final String OUTCOME_APPLIED = "applied";
    public static final String OUTCOME_REFUSED = "refused";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "kind", nullable = false, length = 32)
    private String kind;

    @Column(name = "target_id", nullable = false, length = 36)
    private String targetId;

    @Column(name = "payload")
    private String payload;

    @Column(name = "actor_user_id", length = 36)
    private String actorUserId;

    @Column(name = "made_at", nullable = false)
    private Instant madeAt;

    @Column(name = "pushed_at")
    private Instant pushedAt;

    @Column(name = "outcome", length = 16)
    private String outcome;

    @Column(name = "outcome_detail", length = 500)
    private String outcomeDetail;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @PrePersist
    void onCreate() {
        if (madeAt == null) {
            madeAt = Instant.now();
        }
    }
}
