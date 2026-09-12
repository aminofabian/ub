package zelisline.ub.ai.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * Per-tenant AI logo generation counters. Free allowance is consumed first;
 * further kits debit purchased SMS credits (see {@code AiLogoCreditService}).
 */
@Entity
@Table(name = "business_ai_logo_usage")
@Getter
@Setter
public class BusinessAiLogoUsage {

    @Id
    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "free_used", nullable = false)
    private int freeUsed;

    @Column(name = "paid_count", nullable = false)
    private int paidCount;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
