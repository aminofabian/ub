package zelisline.ub.integrations.whatsapp.domain;

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
 * Super-admin assignment of a Meta WhatsApp number ({@code phone_number_id}) to a shop.
 *
 * <p>Meta credentials themselves live in {@code platform_integration_settings} (super-admin);
 * this table only routes inbound/outbound traffic to the owning {@code business_id}. One
 * platform Meta app can serve many numbers, so this is the multi-tenant mechanism.
 *
 * <p>Managed by super-admin; see {@code docs/scopes/whatsapp-crm/SCOPE.md} §6.2 and
 * {@code docs/adr/0011-whatsapp-crm-boundary.md}.
 */
@Getter
@Setter
@Entity
@Table(name = "whatsapp_channel_route")
public class WhatsAppChannelRoute {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    /** Meta {@code phone_number_id} — unique across the platform. */
    @Column(name = "phone_number_id", nullable = false, length = 64)
    private String phoneNumberId;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    /** E.164 digits for display, e.g. {@code 254712345678} (no leading +). */
    @Column(name = "display_number", length = 32)
    private String displayNumber;

    @Column(name = "label", length = 120)
    private String label;

    @Column(name = "status", nullable = false, length = 16)
    private String status = WhatsAppChannelRouteStatuses.ACTIVE;

    /** Last Meta quality rating observed (GREEN/YELLOW/RED), for super-admin diagnostics. */
    @Column(name = "quality_rating", length = 32)
    private String qualityRating;

    /** When true the route rides the shop's own Meta app (Model B) instead of the platform keys. */
    @Column(name = "own_credentials", nullable = false)
    private boolean ownCredentials;

    /** Graph API version for this route's own credentials; falls back to the platform default. */
    @Column(name = "graph_version", length = 32)
    private String graphVersion;

    /** Encrypted Meta access token when {@link #ownCredentials} (AES-GCM via CredentialEncryptionService). */
    @Column(name = "access_token_enc", columnDefinition = "TEXT")
    private String accessTokenEnc;

    /** Encrypted Meta app secret when {@link #ownCredentials}; widens inbound webhook verification. */
    @Column(name = "app_secret_enc", columnDefinition = "TEXT")
    private String appSecretEnc;

    /** Encrypted Meta webhook verify token when {@link #ownCredentials}. */
    @Column(name = "verify_token_enc", columnDefinition = "TEXT")
    private String verifyTokenEnc;

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
