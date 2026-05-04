package zelisline.ub.tenancy.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "businesses")
public class Business {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "slug", nullable = false, unique = true, length = 191)
    private String slug;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "KES";

    @Column(name = "timezone", nullable = false, length = 100)
    private String timezone = "Africa/Nairobi";

    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode = "KE";

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "subscription_tier", nullable = false, length = 64)
    private String subscriptionTier = "starter";

    @Enumerated(EnumType.STRING)
    @Column(name = "tenant_status", nullable = false, length = 16)
    private TenantStatus tenantStatus = TenantStatus.ACTIVE;

    @Column(name = "settings", nullable = false, columnDefinition = "json")
    private String settings = "{}";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

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
        if (settings == null || settings.isBlank()) {
            settings = "{}";
        }
        if (tenantStatus == null) {
            tenantStatus = TenantStatus.ACTIVE;
        }
        slug = normalize(slug);
        currency = normalizeCode(currency, "KES");
        countryCode = normalizeCode(countryCode, "KE");
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
        slug = normalize(slug);
        currency = normalizeCode(currency, "KES");
        countryCode = normalizeCode(countryCode, "KE");
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase();
    }

    private String normalizeCode(String value, String fallback) {
        String source = (value == null || value.isBlank()) ? fallback : value;
        return source.trim().toUpperCase();
    }
}
