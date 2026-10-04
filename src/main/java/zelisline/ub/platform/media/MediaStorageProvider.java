package zelisline.ub.platform.media;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Where new uploads are stored. Chosen by the super-admin in platform media storage settings. */
public enum MediaStorageProvider {
    CLOUDINARY("cloudinary"),
    R2("r2");

    private final String code;

    MediaStorageProvider(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<MediaStorageProvider> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(p -> p.code.equals(normalized)).findFirst();
    }
}
