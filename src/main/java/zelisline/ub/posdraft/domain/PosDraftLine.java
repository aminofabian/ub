package zelisline.ub.posdraft.domain;

import java.math.BigDecimal;
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
@Table(name = "pos_draft_lines")
public class PosDraftLine {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "draft_id", nullable = false, length = 36)
    private String draftId;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "line_index", nullable = false)
    private int lineIndex;

    /** {@code null} on a service line. */
    @Column(name = "item_id", length = 36)
    private String itemId;

    @Column(name = "line_kind", nullable = false, length = 16)
    private String lineKind = "ITEM";

    @Column(name = "service_id", length = 36)
    private String serviceId;

    /** Service lines: who performs (or performed) it; may be unset until the ticket is started. */
    @Column(name = "performer_staff_profile_id", length = 36)
    private String performerStaffProfileId;

/**
     * Staff who shared this service line with its performer, as {@code id:percent;id:percent}
     * (see {@code ServiceHelpers}). Null when one person did it.
     */
    @Column(name = "service_helpers", length = 400)
    private String serviceHelpers;

    /** What the performer is paid for this service when set at the till; null means by their plan. */
    @Column(name = "performer_amount", precision = 14, scale = 2)
    private java.math.BigDecimal performerAmount;

    /** The chair, bed or room this service used ({@code service_resources.id}); optional. */
    @Column(name = "resource_id", length = 36)
    private String resourceId;

    /** {@code WAITING}, {@code IN_SERVICE} or {@code DONE} for a service line; null on cart lines. */
    @Column(name = "line_stage", length = 16)
    private String lineStage;

    @Column(name = "started_at")
    private java.time.Instant startedAt;

    @Column(name = "finished_at")
    private java.time.Instant finishedAt;

    @Column(name = "item_name", nullable = false, length = 500)
    private String itemName;

    @Column(name = "item_barcode", length = 128)
    private String itemBarcode;

    @Column(name = "quantity", nullable = false, precision = 14, scale = 4)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "discount_amount", nullable = false, precision = 14, scale = 4)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "line_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal lineTotal;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

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
        if (discountAmount == null) {
            discountAmount = BigDecimal.ZERO;
        }
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
