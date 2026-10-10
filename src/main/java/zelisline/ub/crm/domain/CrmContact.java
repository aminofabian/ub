package zelisline.ub.crm.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Chat identity for a WhatsApp conversation. References the platform customer by id;
 * it does not own customer identity (see {@code docs/scopes/whatsapp-crm/SCOPE.md} §6.3).
 */
@Getter
@Setter
@Entity
@Table(name = "crm_contact")
public class CrmContact {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    /** Optional link to the shop's customer; the CRM never owns customer identity. */
    @Column(name = "customer_id", length = 36)
    private String customerId;

    /** WhatsApp number as digits only, e.g. {@code 254712345678}. */
    @Column(name = "phone_e164", nullable = false, length = 20)
    private String phoneE164;

    @Column(name = "name", length = 160)
    private String name;

    @Column(name = "tags_json", columnDefinition = "MEDIUMTEXT")
    private String tagsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }
}
