package zelisline.ub.sales.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "shifts")
public class Shift {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "opened_by", nullable = false, length = 36)
    private String openedBy;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "opening_cash", nullable = false, precision = 14, scale = 2)
    private BigDecimal openingCash;

    @Column(name = "expected_closing_cash", nullable = false, precision = 14, scale = 2)
    private BigDecimal expectedClosingCash;

    @Column(name = "counted_closing_cash", precision = 14, scale = 2)
    private BigDecimal countedClosingCash;

    @Column(name = "closing_variance", precision = 14, scale = 2)
    private BigDecimal closingVariance;

    @Column(name = "opening_notes", length = 2000)
    private String openingNotes;

    @Column(name = "closing_notes", length = 2000)
    private String closingNotes;

    @Column(name = "closed_by", length = 36)
    private String closedBy;

    @Column(name = "close_journal_entry_id", length = 36)
    private String closeJournalEntryId;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

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
        if (openedAt == null) {
            openedAt = now;
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
