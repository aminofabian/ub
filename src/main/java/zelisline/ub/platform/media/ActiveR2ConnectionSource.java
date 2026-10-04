package zelisline.ub.platform.media;

import java.util.Optional;

/** The R2 bucket new uploads should go to, or empty when uploads stay on Cloudinary. */
public interface ActiveR2ConnectionSource {

    Optional<R2Connection> activeR2Connection();
}
