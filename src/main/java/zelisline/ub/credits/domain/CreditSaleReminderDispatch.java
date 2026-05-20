package zelisline.ub.credits.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
        name = "credit_sale_reminder_dispatches",
        uniqueConstraints = @UniqueConstraint(name = "uq_credit_sale_reminder_sale", columnNames = "sale_id"))
public class CreditSaleReminderDispatch {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "sale_id", nullable = false, length = 36)
    private String saleId;

    @Column(name = "customer_id", nullable = false, length = 36)
    private String customerId;

    @Column(name = "channel", nullable = false, length = 24)
    private String channel;

    @Column(name = "outcome", nullable = false, length = 24)
    private String outcome;

    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "message_preview", length = 500)
    private String messagePreview;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (sentAt == null) {
            sentAt = Instant.now();
        }
    }
}
