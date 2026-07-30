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
@Table(name = "domains")
public class DomainMapping {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "domain", nullable = false, unique = true)
    private String domain;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DomainStatus status = DomainStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 32)
    private DomainSource source = DomainSource.MANUAL_CONNECT;

    @Enumerated(EnumType.STRING)
    @Column(name = "zone_source", length = 32)
    private DomainZoneSource zoneSource;

    @Column(name = "hostafrica_domain_id", length = 64)
    private String hostafricaDomainId;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "dns_instruction_json", columnDefinition = "JSON")
    private String dnsInstructionJson;

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
        domain = normalizeDomain(domain);
        if (status == null) {
            status = DomainStatus.ACTIVE;
        }
        if (source == null) {
            source = DomainSource.MANUAL_CONNECT;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
        domain = normalizeDomain(domain);
    }

    private String normalizeDomain(String value) {
        return value == null ? null : value.trim().toLowerCase();
    }
}
