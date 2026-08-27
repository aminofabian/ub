package zelisline.ub.onboarding.sequence.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "merchant_onboarding_enrollment")
public class MerchantOnboardingEnrollment {

    @Id
    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "owner_user_id", nullable = false, length = 36)
    private String ownerUserId;

    @Column(name = "enrolled_at", nullable = false)
    private Instant enrolledAt;

    @Column(name = "muted_at")
    private Instant mutedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "first_sellable_at")
    private Instant firstSellableAt;

    @Column(name = "first_supply_at")
    private Instant firstSupplyAt;

    @Column(name = "first_sale_at")
    private Instant firstSaleAt;

    /** When set, M4 celebration email is due (EOD after first sale). */
    @Column(name = "m4_email_due_at")
    private Instant m4EmailDueAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (enrolledAt == null) {
            enrolledAt = now;
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public static MerchantOnboardingEnrollment enroll(String businessId, String ownerUserId) {
        MerchantOnboardingEnrollment row = new MerchantOnboardingEnrollment();
        row.setBusinessId(businessId);
        row.setOwnerUserId(ownerUserId);
        return row;
    }
}
