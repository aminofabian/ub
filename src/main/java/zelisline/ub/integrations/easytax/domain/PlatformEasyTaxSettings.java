package zelisline.ub.integrations.easytax.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Platform-wide EasyTax configuration (super-admin): whether the integration is
 * available at all, and the EasyTax endpoint for each environment.
 *
 * <p>Tenants never see or edit these URLs — they only choose an environment and
 * enter their own credentials/TIN.
 */
@Getter
@Setter
@Entity
@Table(name = "platform_easytax_settings")
public class PlatformEasyTaxSettings {

    public static final String SINGLETON_ID = "00000000-0000-0000-0000-0000000000e1";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id = SINGLETON_ID;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "sandbox_base_url", length = 255)
    private String sandboxBaseUrl;

    @Column(name = "production_base_url", length = 255)
    private String productionBaseUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String baseUrlFor(EasyTaxEnvironment environment) {
        return environment == EasyTaxEnvironment.PRODUCTION ? productionBaseUrl : sandboxBaseUrl;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null || id.isBlank()) {
            id = SINGLETON_ID;
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
