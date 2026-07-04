package zelisline.ub.storefront.domain;

import java.time.Instant;
import java.util.UUID;

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
@Table(name = "web_checkout_sessions")
public class WebCheckoutSession {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "cart_id", nullable = false, length = 36)
    private String cartId;

    @Column(name = "guest_key", length = 64)
    private String guestKey;

    @Column(name = "first_name", length = 120)
    private String firstName;

    @Column(name = "last_name", length = 120)
    private String lastName;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "area_code", length = 16)
    private String areaCode;

    @Column(name = "phone", length = 64)
    private String phone;

    @Column(name = "whatsapp", length = 64)
    private String whatsapp;

    @Column(name = "county", length = 120)
    private String county;

    @Column(name = "subcounty", length = 120)
    private String subcounty;

    @Column(name = "ward", length = 120)
    private String ward;

    @Column(name = "street_address", length = 500)
    private String streetAddress;

    @Column(name = "delivery_notes", length = 1000)
    private String deliveryNotes;

    @Column(name = "contact_completed_at")
    private Instant contactCompletedAt;

    @Column(name = "delivery_completed_at")
    private Instant deliveryCompletedAt;

    @Column(name = "save_for_next_time", nullable = false)
    private boolean saveForNextTime;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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
