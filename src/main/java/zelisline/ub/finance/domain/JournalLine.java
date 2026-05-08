package zelisline.ub.finance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

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
@Table(name = "journal_lines")
public class JournalLine {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "journal_entry_id", nullable = false, length = 36)
    private String journalEntryId;

    @Column(name = "ledger_account_id", nullable = false, length = 36)
    private String ledgerAccountId;

    @Column(name = "debit", nullable = false, precision = 14, scale = 2)
    private BigDecimal debit = BigDecimal.ZERO;

    @Column(name = "credit", nullable = false, precision = 14, scale = 2)
    private BigDecimal credit = BigDecimal.ZERO;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
    }

    public static JournalLine debit(String ledgerAccountId, BigDecimal amount) {
        JournalLine l = new JournalLine();
        l.setLedgerAccountId(ledgerAccountId);
        l.setDebit(amount.setScale(2, RoundingMode.HALF_UP));
        l.setCredit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        return l;
    }

    public static JournalLine credit(String ledgerAccountId, BigDecimal amount) {
        JournalLine l = new JournalLine();
        l.setLedgerAccountId(ledgerAccountId);
        l.setDebit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        l.setCredit(amount.setScale(2, RoundingMode.HALF_UP));
        return l;
    }
}
