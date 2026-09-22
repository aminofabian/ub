package zelisline.ub.payments.domain;

import java.math.BigDecimal;
import java.time.Instant;

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
@Table(name = "profit_pocket_settings")
public class ProfitPocketSettings {

    public static final String TYPE_BANK = "bank";
    public static final String TYPE_MPESA_PHONE = "mpesa_phone";
    public static final String TYPE_TILL = "till";
    public static final String TYPE_PAYBILL = "paybill";

    public static final String GUARD_WARN = "warn";
    public static final String GUARD_APPROVE = "approve";
    public static final String GUARD_HARD = "hard";

    @Id
    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "destination_type", length = 32)
    private String destinationType;

    @Column(name = "destination_label", length = 120)
    private String destinationLabel;

    @Column(name = "destination_account", length = 64)
    private String destinationAccount;

    @Column(name = "destination_bank_name", length = 120)
    private String destinationBankName;

    @Column(name = "destination_paybill", length = 32)
    private String destinationPaybill;

    @Column(name = "destination_paybill_account", length = 64)
    private String destinationPaybillAccount;

    @Column(name = "default_float", nullable = false, precision = 14, scale = 2)
    private BigDecimal defaultFloat = new BigDecimal("5000.00");

    /** warn (default) | approve | hard — below-cost till policy. */
    @Column(name = "margin_guard_mode", nullable = false, length = 16)
    private String marginGuardMode = GUARD_WARN;

    @Column(name = "friday_reminder_enabled", nullable = false)
    private boolean fridayReminderEnabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (defaultFloat == null) {
            defaultFloat = new BigDecimal("5000.00");
        }
        if (marginGuardMode == null || marginGuardMode.isBlank()) {
            marginGuardMode = GUARD_WARN;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public static ProfitPocketSettings disabledFor(String businessId) {
        ProfitPocketSettings row = new ProfitPocketSettings();
        row.setBusinessId(businessId);
        row.setEnabled(false);
        row.setDefaultFloat(new BigDecimal("5000.00"));
        row.setMarginGuardMode(GUARD_WARN);
        row.setFridayReminderEnabled(true);
        return row;
    }
}
