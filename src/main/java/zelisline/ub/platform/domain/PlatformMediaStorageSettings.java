package zelisline.ub.platform.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "platform_media_storage_settings")
@Getter
@Setter
public class PlatformMediaStorageSettings {

    public static final String SINGLETON_ID = "00000000-0000-0000-0000-000000000001";

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    /** {@code cloudinary} or {@code r2}; see {@code MediaStorageProvider}. */
    @Column(name = "upload_provider", length = 16, nullable = false)
    private String uploadProvider = "cloudinary";

    @Column(name = "r2_account_id", length = 64)
    private String r2AccountId;

    @Column(name = "r2_endpoint", length = 512)
    private String r2Endpoint;

    @Column(name = "r2_bucket", length = 128)
    private String r2Bucket;

    @Column(name = "r2_access_key_id_enc", columnDefinition = "TEXT")
    private String r2AccessKeyIdEnc;

    @Column(name = "r2_secret_access_key_enc", columnDefinition = "TEXT")
    private String r2SecretAccessKeyEnc;

    @Column(name = "r2_public_base_url", length = 512)
    private String r2PublicBaseUrl;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
