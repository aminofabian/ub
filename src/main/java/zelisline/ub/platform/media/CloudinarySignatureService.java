package zelisline.ub.platform.media;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

@Service
public class CloudinarySignatureService {

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

    public SignatureResult signUpload(String folder) {
        if (!isConfigured()) {
            throw new IllegalStateException("Cloudinary is not configured");
        }

        long timestamp = Instant.now().getEpochSecond();

        Map<String, String> params = new TreeMap<>();
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
                folder != null ? folder.trim() : null
        );
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
            String folder
    ) {
    }
}
