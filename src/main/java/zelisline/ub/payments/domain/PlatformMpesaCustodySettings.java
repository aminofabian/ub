package zelisline.ub.payments.domain;

import java.time.Instant;

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
@Table(name = "platform_mpesa_custody_settings")
public class PlatformMpesaCustodySettings {

    public static final String SINGLETON_ID = "00000000-0000-0000-0000-000000000003";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id = SINGLETON_ID;

    /** {@link PlatformMpesaCustodyProviders}: OFF | KOPOKOPO | DARAJA */
    @Column(name = "custody_provider", nullable = false, length = 16)
    private String custodyProvider = PlatformMpesaCustodyProviders.OFF;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        if (id == null || id.isBlank()) {
            id = SINGLETON_ID;
        }
        updatedAt = Instant.now();
        if (custodyProvider == null || custodyProvider.isBlank()) {
            custodyProvider = PlatformMpesaCustodyProviders.OFF;
        }
    }
}
