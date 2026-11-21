package zelisline.ub.pharmacy.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * Marks a catalogue item as a medicine. Its presence switches on the stock-safety rules (lot and
 * expiry on receive, earliest-expiry-first, never oversold) and its {@link #dispensingClass}
 * decides what checkout requires. Absent for every non-medicine item.
 */
@Getter
@Setter
@Entity
@Table(name = "item_medicine_details")
public class ItemMedicineDetails {

    @Id
    @Column(name = "item_id", nullable = false, length = 36)
    private String itemId;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "generic_name", length = 255)
    private String genericName;

    @Column(name = "strength", length = 64)
    private String strength;

    @Column(name = "dosage_form", length = 64)
    private String dosageForm;

    @Column(name = "pack_size", length = 64)
    private String packSize;

    @Column(name = "manufacturer", length = 191)
    private String manufacturer;

    @Column(name = "registration_no", length = 64)
    private String registrationNo;

    @Column(name = "storage_note", length = 255)
    private String storageNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "dispensing_class", nullable = false, length = 24)
    private DispensingClass dispensingClass = DispensingClass.UNCLASSIFIED;

    /**
     * The class the platform catalogue suggested when this medicine was adopted. Shown to the
     * pharmacist for confirmation; it never decides what the till requires.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "suggested_class", length = 24)
    private DispensingClass suggestedClass;

    /**
     * When true (and the class is OTC) a refunded unit goes back on the shelf, as for any other
     * product. Otherwise a refunded medicine is quarantined for disposal.
     */
    @Column(name = "restock_on_refund", nullable = false)
    private boolean restockOnRefund;

    @Column(name = "class_set_by", length = 36)
    private String classSetBy;

    @Column(name = "class_set_at")
    private Instant classSetAt;

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
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Refunded units of this medicine must not return to sellable stock. */
    public boolean quarantineOnRefund() {
        return !(dispensingClass == DispensingClass.OTC && restockOnRefund);
    }
}
