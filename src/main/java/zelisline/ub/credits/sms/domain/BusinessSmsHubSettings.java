package zelisline.ub.credits.sms.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Per-shop SMS Hub settings: which automatic texts are on and how they are worded.
 * A shop with no row uses the defaults (order updates on with standard wording,
 * thank-you and win-back off, one promotion per customer per week).
 */
@Getter
@Setter
@Entity
@Table(name = "business_sms_hub_settings")
public class BusinessSmsHubSettings {

    public static final int DEFAULT_WINBACK_DAYS = 30;
    public static final int DEFAULT_WEEKLY_CAP = 1;

    @Id
    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    /** JSON object keyed by order notice: {"confirmed": {"enabled": true, "template": null}, …}. */
    @Column(name = "order_notices_json", columnDefinition = "text")
    private String orderNoticesJson;

    @Column(name = "thank_you_enabled", nullable = false)
    private boolean thankYouEnabled;

    @Column(name = "thank_you_template", length = 480)
    private String thankYouTemplate;

    @Column(name = "winback_enabled", nullable = false)
    private boolean winbackEnabled;

    @Column(name = "winback_days", nullable = false)
    private int winbackDays = DEFAULT_WINBACK_DAYS;

    @Column(name = "winback_template", length = 480)
    private String winbackTemplate;

    @Column(name = "promo_weekly_cap", nullable = false)
    private int promoWeeklyCap = DEFAULT_WEEKLY_CAP;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
