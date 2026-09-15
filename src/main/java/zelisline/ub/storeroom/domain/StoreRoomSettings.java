package zelisline.ub.storeroom.domain;

import java.math.BigDecimal;
import java.time.Instant;

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
 * Per-business store-room connection choice. One row per business, created lazily
 * the first time a merchant answers the standalone-vs-connected prompt.
 */
@Getter
@Setter
@Entity
@Table(name = "store_room_settings")
public class StoreRoomSettings {

    @Id
    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    /** {@code null} until the merchant chooses. */
    @Enumerated(EnumType.STRING)
    @Column(name = "mode", length = 16)
    private StoreRoomMode mode;

    @Column(name = "connected_at")
    private Instant connectedAt;

    /**
     * Ask for approval before more than this leaves stock. {@code null} = never ask.
     * Only applies to linked rows in connected mode — a local count is low stakes.
     */
    @Column(name = "approval_threshold", precision = 14, scale = 4)
    private BigDecimal approvalThreshold;

    /**
     * When true, nobody may approve a take-out they raised themselves (§10 D7). Default
     * off, because a one-person shop has no second person to ask.
     */
    @Column(name = "require_separate_approver", nullable = false)
    private boolean requireSeparateApprover;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
