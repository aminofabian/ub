package zelisline.ub.platform.media;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Ensures a {@link MediaStore} bean is always available.
 *
 * <p>The cloud SKU activates {@link CloudinaryImageService} by default; the desktop SKU
 * activates {@link LocalMediaStore}. This no-op fallback exists only when BOTH are off,
 * so exactly one {@link MediaStore} bean is created in every profile (a
 * {@code @ConditionalOnMissingBean} here races bean-definition ordering against
 * {@code CloudinaryImageService} and can produce two beans in the cloud default).
 */
@Configuration
public class MediaStoreConfiguration {

    private static final CloudinaryUploadResult EMPTY =
            new CloudinaryUploadResult(null, null, null, null, null, null, null, null, null, null);

    @Bean
    @ConditionalOnProperty(name = "app.media.cloudinary.enabled", havingValue = "false")
    @ConditionalOnProperty(name = "app.media.local.enabled", havingValue = "false", matchIfMissing = true)
    public MediaStore noOpMediaStore() {
        return new MediaStore() {
            @Override
            public boolean isConfigured() {
                return false;
            }

            @Override
            public CloudinaryUploadResult uploadImage(byte[] fileBytes, String originalFilename, String businessId, String itemId) {
                return EMPTY;
            }

            @Override
            public CloudinaryUploadResult uploadImageToFolder(byte[] fileBytes, String originalFilename, String folderPath) {
                return EMPTY;
            }

            @Override
            public CloudinaryUploadResult uploadImageToFolder(
                    byte[] fileBytes,
                    String originalFilename,
                    String folderPath,
                    boolean requestImageFingerprinting
            ) {
                return EMPTY;
            }

            @Override
            public CloudinaryUploadResult uploadFromRemoteUrl(String remoteUrl, String folderPath) {
                return EMPTY;
            }

            @Override
            public void destroyImage(String publicId) {
                // no-op
            }
        };
    }
}
