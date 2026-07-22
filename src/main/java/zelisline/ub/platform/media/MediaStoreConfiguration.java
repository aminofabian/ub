package zelisline.ub.platform.media;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Ensures a {@link MediaStore} bean is always available.
 *
 * <p>The cloud SKU activates {@link CloudinaryImageService} by default; the desktop SKU
 * activates {@link LocalMediaStore}. In profiles where both are disabled (e.g. integration
 * tests), this configuration provides a no-op fallback so services that inject
 * {@link MediaStore} can still start.
 */
@Configuration
public class MediaStoreConfiguration {

    private static final CloudinaryUploadResult EMPTY =
            new CloudinaryUploadResult(null, null, null, null, null, null, null, null, null, null);

    @Bean
    @ConditionalOnMissingBean(MediaStore.class)
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
