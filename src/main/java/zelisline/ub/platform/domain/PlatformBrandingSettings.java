package zelisline.ub.platform.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Singleton platform-branding row, editable in Super Admin. Currently holds the
 * logo used in platform transactional email (onboarding sequence, campaigns).
 * Blank logo falls back to the built-in "K" wordmark badge.
 */
@Entity
@Table(name = "platform_branding_settings")
@Getter
@Setter
public class PlatformBrandingSettings {

    public static final String SINGLETON_ID = "00000000-0000-0000-0000-000000000001";

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "email_logo_url", length = 1024)
    private String emailLogoUrl;

    @Column(name = "email_logo_public_id", length = 512)
    private String emailLogoPublicId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
