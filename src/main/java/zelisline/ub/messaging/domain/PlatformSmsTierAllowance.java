package zelisline.ub.messaging.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Included SMS per subscription tier, editable in Super Admin without deploy.
 * {@code businesses.subscription_tier} is looked up here (SMS_CREDITS_SCOPE.md §5).
 */
@Entity
@Table(name = "platform_sms_tier_allowances")
@Getter
@Setter
public class PlatformSmsTierAllowance {

    @Id
    @Column(name = "tier_code", nullable = false, length = 64)
    private String tierCode;

    @Column(name = "included_sms_per_month", nullable = false)
    private int includedSmsPerMonth;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
