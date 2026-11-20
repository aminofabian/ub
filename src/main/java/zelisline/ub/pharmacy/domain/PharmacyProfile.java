package zelisline.ub.pharmacy.domain;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * What a shop has told us about its pharmacy. Recording a licence number here does not make a
 * shop licensed; the shop and its pharmacist remain responsible for that.
 */
@Getter
@Setter
@Entity
@Table(name = "pharmacy_profiles")
public class PharmacyProfile {

    @Id
    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "premises_licence_no", length = 64)
    private String premisesLicenceNo;

    @Column(name = "licence_expires_on")
    private LocalDate licenceExpiresOn;

    @Column(name = "superintendent_user_id", length = 36)
    private String superintendentUserId;

    /** Lets CONTROLLED medicines be sold before the controlled-stock register exists. */
    @Column(name = "controlled_sales_enabled", nullable = false)
    private boolean controlledSalesEnabled;

    @Column(name = "short_dated_days", nullable = false)
    private int shortDatedDays = 90;

    /** A second person who may dispense checks each dispensing record. */
    @Column(name = "second_check_enabled", nullable = false)
    private boolean secondCheckEnabled;

    /** Days a supplier takes to deliver, for the ordering forecast. */
    @Column(name = "order_lead_days", nullable = false)
    private int orderLeadDays = 7;

    /** Days of stock an order should cover, for the ordering forecast. */
    @Column(name = "order_cover_days", nullable = false)
    private int orderCoverDays = 30;

    /** The licence expiry date the last reminder was sent for. */
    @Column(name = "licence_reminder_sent_for")
    private LocalDate licenceReminderSentFor;

    /** 0 = none sent, 1 = 60-day notice sent, 2 = 14-day notice sent, for licenceReminderSentFor. */
    @Column(name = "licence_reminder_stage", nullable = false)
    private int licenceReminderStage;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
