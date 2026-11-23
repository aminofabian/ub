package zelisline.ub.wholesale.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One piece of a person's own work in a supplier's shop. Append-only; written once per source. */
@Entity
@Table(name = "supplier_contributions")
@Getter
@Setter
@NoArgsConstructor
public class SupplierContribution {

    /** Rule, version 1: answered a trade order within a working day. Source: the sales order. */
    public static final String RULE_ANSWERED_WITHIN_A_WORKING_DAY = "answered_within_a_working_day";
    public static final String SOURCE_SALES_ORDER = "sales_order";
    /** Rule, version 1: made a product one a buyer can order. Source: the item, counted once for the shop. */
    public static final String RULE_MADE_PRODUCT_ORDERABLE = "made_product_orderable";
    public static final String SOURCE_ITEM = "item";
    public static final int RULE_VERSION = 1;

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "rule_id", nullable = false, length = 64)
    private String ruleId;

    @Column(name = "rule_version", nullable = false)
    private int ruleVersion = RULE_VERSION;

    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType;

    @Column(name = "source_id", nullable = false, length = 36)
    private String sourceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public SupplierContribution(String businessId, String userId, String ruleId, String sourceType, String sourceId) {
        this.businessId = businessId;
        this.userId = userId;
        this.ruleId = ruleId;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
    }

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
