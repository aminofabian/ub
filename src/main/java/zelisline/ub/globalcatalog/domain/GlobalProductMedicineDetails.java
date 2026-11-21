package zelisline.ub.globalcatalog.domain;

import java.time.Instant;

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
 * Marks a catalogue product as a medicine and carries what a shop's medicine record starts with
 * when the product is adopted (ADR 0033).
 *
 * <p>{@link #suggestedClass} is a suggestion from whoever curates the catalogue. It is copied to
 * the shop as a suggestion and never becomes the shop's dispensing class on its own.</p>
 */
@Getter
@Setter
@Entity
@Table(name = "global_product_medicine_details")
public class GlobalProductMedicineDetails {

    @Id
    @Column(name = "global_product_id", nullable = false, length = 36)
    private String globalProductId;

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

    /** A {@code DispensingClass} name other than UNCLASSIFIED, or null for no suggestion. */
    @Column(name = "suggested_class", length = 24)
    private String suggestedClass;

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
}
