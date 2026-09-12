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

/**
 * Singleton platform Safaricom Daraja settings (API keys in SA DB, not env).
 *
 * <p>When enabled, Party B for platform-initiated STK is {@link #shortcode}
 * (Paybill or Buy Goods till). Tenant BYO Daraja configs remain separate.
 */
@Getter
@Setter
@Entity
@Table(name = "platform_daraja_settings")
public class PlatformDarajaSettings {

    public static final String SINGLETON_ID = "00000000-0000-0000-0000-000000000003";

    /** Synthetic config id on {@code gateway_stk_pushes} for platform Daraja STK. */
    public static final String PLATFORM_DARAJA_CONFIG_ID = "platform-daraja";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id = SINGLETON_ID;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "environment", nullable = false, length = 16)
    private String environment = "sandbox";

    /** {@code paybill} or {@code till}. */
    @Column(name = "shortcode_type", nullable = false, length = 16)
    private String shortcodeType = "paybill";

    @Column(name = "shortcode", length = 32)
    private String shortcode;

    /** AES-GCM JSON: consumerKey, consumerSecret, passkey (+ mirrored shortcode fields). */
    @Column(name = "credentials_enc", columnDefinition = "TEXT")
    private String credentialsEnc;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        if (id == null || id.isBlank()) {
            id = SINGLETON_ID;
        }
        updatedAt = Instant.now();
    }
}
