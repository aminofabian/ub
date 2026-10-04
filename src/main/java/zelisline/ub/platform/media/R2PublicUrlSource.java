package zelisline.ub.platform.media;

import java.util.Optional;

/**
 * The saved R2 public base URL (no trailing slash), present even while uploads are on
 * Cloudinary so files already stored in R2 remain acceptable links.
 */
public interface R2PublicUrlSource {

    Optional<String> r2PublicBaseUrl();
}
