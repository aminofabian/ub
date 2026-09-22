package zelisline.ub.finance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "profit_pockets")
public class ProfitPocket {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", length = 36)
    private String branchId;

    @Column(name = "period_from", nullable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", nullable = false)
    private LocalDate periodTo;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "leave_float", nullable = false, precision = 14, scale = 2)
    private BigDecimal leaveFloat = BigDecimal.ZERO;

    @Column(name = "suggested_surplus", precision = 14, scale = 2)
    private BigDecimal suggestedSurplus;

    @Column(name = "funding_method", nullable = false, length = 32)
    private String fundingMethod;

    @Column(name = "destination_type", nullable = false, length = 32)
    private String destinationType;

    @Column(name = "destination_snapshot_json", nullable = false, columnDefinition = "TEXT")
    private String destinationSnapshotJson;

    @Column(name = "warnings_json", columnDefinition = "TEXT")
    private String warningsJson;

    @Column(name = "journal_entry_id", length = 36)
    private String journalEntryId;

    /** pending | success | failed | skipped (bank / no gateway) | null */
    @Column(name = "send_money_status", length = 32)
    private String sendMoneyStatus;

    @Column(name = "kopokopo_send_money_id", length = 64)
    private String kopokopoSendMoneyId;

    @Column(name = "payment_gateway_config_id", length = 36)
    private String paymentGatewayConfigId;

    @Column(name = "send_money_message", length = 500)
    private String sendMoneyMessage;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (leaveFloat == null) {
            leaveFloat = BigDecimal.ZERO;
        }
    }
}
