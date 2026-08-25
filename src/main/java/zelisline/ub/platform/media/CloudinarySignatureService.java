package zelisline.ub.platform.media;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

@Service
public class CloudinarySignatureService {

    public static final String RESOURCE_IMAGE = "image";
    public static final String RESOURCE_AUTO = "auto";
    public static final String RESOURCE_RAW = "raw";

    private final CloudinaryProperties properties;

    public CloudinarySignatureService(CloudinaryProperties properties) {
        this.properties = properties;
    }

    public boolean isConfigured() {
        return properties.isEnabled()
                && !properties.getCloudName().isBlank()
                && !properties.getApiKey().isBlank()
                && !properties.getApiSecret().isBlank();
    }

    /** Image uploads — includes phash/colors extras used by catalog media. */
    public SignatureResult signUpload(String folder) {
        return signUpload(folder, RESOURCE_IMAGE);
    }

    /**
     * Signed upload for Cloudinary {@code image|auto|raw} endpoints.
     * Image mode keeps phash/colors; auto/raw omit those (they break non-image uploads).
     */
    public SignatureResult signUpload(String folder, String resourceType) {
        if (!isConfigured()) {
            throw new IllegalStateException("Cloudinary is not configured");
        }

        String type = normalizeResourceType(resourceType);
        long timestamp = Instant.now().getEpochSecond();

        Map<String, String> params = new TreeMap<>();
        if (RESOURCE_IMAGE.equals(type)) {
            params.put("colors", "true");
            params.put("phash", "true");
        }
        if (folder != null && !folder.isBlank()) {
            params.put("folder", folder.trim());
        }
        params.put("timestamp", String.valueOf(timestamp));

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append("&");
            }
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        sb.append(properties.getApiSecret());

        String signature = sha1Hex(sb.toString());

        return new SignatureResult(
                properties.getCloudName(),
                properties.getApiKey(),
                timestamp,
                signature,
                folder != null ? folder.trim() : null,
                type
        );
    }

    private static String normalizeResourceType(String resourceType) {
        if (resourceType == null || resourceType.isBlank()) {
            return RESOURCE_IMAGE;
        }
        String t = resourceType.trim().toLowerCase(Locale.ROOT);
        if (RESOURCE_IMAGE.equals(t) || RESOURCE_AUTO.equals(t) || RESOURCE_RAW.equals(t)) {
            return t;
        }
        throw new IllegalArgumentException("resourceType must be image, auto, or raw");
    }

    private static String sha1Hex(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record SignatureResult(
            String cloudName,
            String apiKey,
            long timestamp,
            String signature,
            String folder,
            String resourceType
    ) {
        public SignatureResult(
                String cloudName,
                String apiKey,
                long timestamp,
                String signature,
                String folder
        ) {
            this(cloudName, apiKey, timestamp, signature, folder, RESOURCE_IMAGE);
        }
    }
}
