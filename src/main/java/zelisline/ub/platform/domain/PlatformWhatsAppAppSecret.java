package zelisline.ub.platform.domain;

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
 * An additional platform Meta app secret accepted for inbound WhatsApp webhook verification.
 *
 * <p>The primary platform secret lives on
 * {@link PlatformIntegrationSettings#getWhatsappMetaAppSecretEnc()}. A WhatsApp Business Account can
 * have several Meta apps subscribed and Meta signs each delivery with the sending app's secret, so
 * more than one secret may need to verify. See {@code docs/scopes/whatsapp-crm/KEYS.md}.
 */
@Getter
@Setter
@Entity
@Table(name = "platform_whatsapp_app_secret")
public class PlatformWhatsAppAppSecret {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    /** Optional human label (e.g. the Meta app name) so the console can tell entries apart. */
    @Column(name = "label", length = 120)
    private String label;

    /** Encrypted Meta app secret (AES-256-GCM via {@code CredentialEncryptionService}). */
    @Column(name = "app_secret_enc", columnDefinition = "TEXT", nullable = false)
    private String appSecretEnc;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
