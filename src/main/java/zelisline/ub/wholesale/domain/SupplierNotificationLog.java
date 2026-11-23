package zelisline.ub.wholesale.domain;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The last day a supplier was sent a note. Keeps the budget of at most one a day. */
@Entity
@Table(name = "supplier_notification_log")
@Getter
@Setter
@NoArgsConstructor
public class SupplierNotificationLog {

    @Id
    @Column(name = "business_id", length = 36)
    private String businessId;

    @Column(name = "last_sent_on", nullable = false)
    private LocalDate lastSentOn;

    public SupplierNotificationLog(String businessId, LocalDate lastSentOn) {
        this.businessId = businessId;
        this.lastSentOn = lastSentOn;
    }
}
