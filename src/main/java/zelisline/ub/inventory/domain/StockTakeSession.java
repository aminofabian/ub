package zelisline.ub.inventory.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import zelisline.ub.inventory.InventoryConstants;

@Getter
@Setter
@Entity
@Table(name = "stock_take_sessions")
public class StockTakeSession {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "session_type", nullable = false, length = 16)
    private String sessionType;

    @Column(name = "session_date", nullable = false)
    private LocalDate sessionDate;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "session_number", nullable = false)
    private int sessionNumber;

    @Column(name = "started_by", length = 36)
    private String startedBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by", length = 36)
    private String closedBy;

    @Column(name = "source", nullable = false, length = 32)
    private String source = InventoryConstants.STOCKTAKE_SOURCE_MANUAL;

    @Column(name = "daily_audit_id", length = 36)
    private String dailyAuditId;

    @Column(name = "current_line_index")
    private Integer currentLineIndex;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<StockTakeLine> lines = new ArrayList<>();

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
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
