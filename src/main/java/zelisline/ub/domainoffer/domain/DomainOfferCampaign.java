package zelisline.ub.domainoffer.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One run of the "own your address" offer. Super Admin starts it once; the
 * scheduler then fans out three touches (day 0, 3, 6) to every enrolled shop.
 */
@Getter
@Setter
@Entity
@Table(name = "domain_offer_campaigns")
public class DomainOfferCampaign {

    public static final String AUDIENCE_NO_CUSTOM_DOMAIN = "no_custom_domain";
    public static final long DEFAULT_PRICE_CENTS = 100_000L;
    public static final int DEFAULT_INTERVAL_DAYS = 3;
    public static final int DEFAULT_MAX_TOUCHES = 3;

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "name", nullable = false, length = 191)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private DomainOfferCampaignStatus status = DomainOfferCampaignStatus.DRAFT;

    @Column(name = "audience_key", nullable = false, length = 64)
    private String audienceKey = AUDIENCE_NO_CUSTOM_DOMAIN;

    @Column(name = "price_cents", nullable = false)
    private long priceCents = DEFAULT_PRICE_CENTS;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency = "KES";

    @Column(name = "interval_days", nullable = false)
    private int intervalDays = DEFAULT_INTERVAL_DAYS;

    @Column(name = "max_touches", nullable = false)
    private int maxTouches = DEFAULT_MAX_TOUCHES;

    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled = true;

    @Column(name = "in_app_enabled", nullable = false)
    private boolean inAppEnabled = true;

    @Column(name = "recipients_targeted", nullable = false)
    private int recipientsTargeted;

    @Column(name = "created_by_super_admin_id", length = 36)
    private String createdBySuperAdminId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "paused_at")
    private Instant pausedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (status == null) {
            status = DomainOfferCampaignStatus.DRAFT;
        }
        if (currency == null || currency.isBlank()) {
            currency = "KES";
        }
        if (intervalDays <= 0) {
            intervalDays = DEFAULT_INTERVAL_DAYS;
        }
        if (maxTouches <= 0) {
            maxTouches = DEFAULT_MAX_TOUCHES;
        }
        if (priceCents <= 0) {
            priceCents = DEFAULT_PRICE_CENTS;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
