package zelisline.ub.platform.media;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@code CLOUDINARY_URL} as {@code cloudinary://API_KEY:API_SECRET@CLOUD_NAME}.
 */
public final class CloudinaryUrlParser {

    private static final Pattern CLOUDINARY_URL = Pattern.compile(
            "^cloudinary://([^:\\s]+):([^@\\s]+)@([^/\\s?#]+)/?$",
            Pattern.CASE_INSENSITIVE
    );

    public record Parts(String apiKey, String apiSecret, String cloudName) {
    }

    private CloudinaryUrlParser() {
    }

    public static Optional<Parts> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String u = raw.trim();
        if (u.isEmpty()) {
            return Optional.empty();
        }
        if (!u.regionMatches(true, 0, "cloudinary://", 0, 13)) {
            return Optional.empty();
        }
        Matcher m = CLOUDINARY_URL.matcher(u);
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Parts(
                m.group(1).trim(),
                m.group(2).trim(),
                normalizeCloudName(m.group(3))
        ));
    }

    private static String normalizeCloudName(String raw) {
        String s = raw.trim();
        if (s.endsWith(".")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }
}
