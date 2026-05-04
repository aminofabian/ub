package zelisline.ub.platform.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;

@Service
public class CloudinaryImageService {

    private static final int MAX_UPLOAD_BYTES = 12 * 1024 * 1024;

    private final CloudinaryProperties properties;
    private final ObjectMapper objectMapper;

    public CloudinaryImageService(CloudinaryProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        if (!properties.isEnabled()) {
            return false;
        }
        return !properties.getCloudName().isBlank()
                && !properties.getApiKey().isBlank()
                && !properties.getApiSecret().isBlank();
    }

    public CloudinaryUploadResult uploadImage(
            byte[] fileBytes,
            String originalFilename,
            String businessId,
            String itemId
    ) {
        return uploadImageToFolder(fileBytes, originalFilename, folderItems(businessId, itemId), true);
    }

    /**
     * Same as {@link #uploadImageToFolder(byte[], String, String, boolean)} with
     * {@code requestImageFingerprinting = true} (phash + predominant colors).
     */
    public CloudinaryUploadResult uploadImageToFolder(
            byte[] fileBytes,
            String originalFilename,
            String folderPath
    ) {
        return uploadImageToFolder(fileBytes, originalFilename, folderPath, true);
    }

    /**
     * Folder path under the Cloudinary account (e.g. {@code ub/{businessId}/categories/{id}}).
     *
     * @param requestImageFingerprinting when {@code true}, sends {@code phash} and {@code colors}
     *        to Cloudinary (useful for catalog images). When {@code false}, omits them — favicons
     *        and multi-resolution {@code .ico} files often fail or time out when those analyses run.
     */
    public CloudinaryUploadResult uploadImageToFolder(
            byte[] fileBytes,
            String originalFilename,
            String folderPath,
            boolean requestImageFingerprinting
    ) {
        if (!isConfigured()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Cloudinary is not configured on this server"
            );
        }
        if (fileBytes == null || fileBytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty image file");
        }
        if (fileBytes.length > MAX_UPLOAD_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Image exceeds size limit");
        }
        String safeName = originalFilename == null || originalFilename.isBlank()
                ? "upload.bin"
                : originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
        String folder = folderPath == null || folderPath.isBlank()
                ? "ub/misc"
                : folderPath.trim();

        String url = "https://api.cloudinary.com/v1_1/" + properties.getCloudName() + "/image/upload";
        var req = Unirest.post(url)
                .basicAuth(properties.getApiKey(), properties.getApiSecret())
                .field("file", new ByteArrayInputStream(fileBytes), kong.unirest.ContentType.APPLICATION_OCTET_STREAM, safeName)
                .field("folder", folder)
                .field("overwrite", "false");
        if (requestImageFingerprinting) {
            req = req.field("phash", "true").field("colors", "true");
        }
        final HttpResponse<String> raw;
        try {
            raw = req.asString();
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not reach Cloudinary: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
            );
        }

        if (raw.getStatus() != 200) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    cloudinaryFailureMessage("upload", raw.getStatus(), raw.getBody()));
        }
        try {
            return parseUpload(raw.getBody());
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not parse Cloudinary response: " + e.getMessage()
            );
        }
    }

    public static String folderItems(String businessId, String itemId) {
        return "ub/" + businessId + "/items/" + itemId;
    }

    public static String folderCategories(String businessId, String categoryId) {
        return "ub/" + businessId + "/categories/" + categoryId;
    }

    public void destroyImage(String publicId) {
        if (!isConfigured() || publicId == null || publicId.isBlank()) {
            return;
        }
        String url = "https://api.cloudinary.com/v1_1/" + properties.getCloudName() + "/image/destroy";
        final HttpResponse<String> raw;
        try {
            raw = Unirest.post(url)
                    .basicAuth(properties.getApiKey(), properties.getApiSecret())
                    .field("public_id", publicId)
                    .asString();
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not reach Cloudinary (destroy): "
                            + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
            );
        }
        if (raw.getStatus() != 200) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    cloudinaryFailureMessage("destroy", raw.getStatus(), raw.getBody()));
        }
    }

    private String cloudinaryFailureMessage(String op, int httpStatus, String body) {
        String base = "Cloudinary " + op + " failed: HTTP " + httpStatus;
        if (body == null || body.isBlank()) {
            return base;
        }
        try {
            JsonNode err = objectMapper.readTree(body);
            String msg = err.path("error").path("message").asText(null);
            if (msg != null && !msg.isBlank()) {
                return base + " — " + msg;
            }
        } catch (Exception ignored) {
            /* fall through */
        }
        String truncated = body.length() > 280 ? body.substring(0, 280) + "…" : body;
        return base + " — " + truncated;
    }

    private CloudinaryUploadResult parseUpload(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        if (root.hasNonNull("error")) {
            String msg = root.get("error").path("message").asText("upload rejected");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
        }
        String publicId = root.path("public_id").asText(null);
        String secureUrl = root.path("secure_url").asText(null);
        if (publicId == null || secureUrl == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Cloudinary response missing public_id or secure_url");
        }
        Integer width = root.has("width") && root.get("width").canConvertToInt() ? root.get("width").asInt() : null;
        Integer height = root.has("height") && root.get("height").canConvertToInt() ? root.get("height").asInt() : null;
        long bytes = root.path("bytes").asLong(0L);
        String format = root.path("format").asText(null);
        String version = root.has("version") ? root.get("version").asText(null) : null;
        String phash = root.path("phash").asText(null);
        String hex = readPredominantHex(root);
        String contentType = toContentType(format);
        return new CloudinaryUploadResult(
                publicId,
                secureUrl,
                width,
                height,
                bytes > 0 ? bytes : null,
                format,
                contentType,
                version,
                hex,
                phash
        );
    }

    private static String readPredominantHex(JsonNode root) {
        JsonNode colors = root.get("colors");
        if (colors != null && colors.isArray() && colors.size() > 0) {
            JsonNode first = colors.get(0);
            if (first != null && first.isArray() && first.size() > 0) {
                return first.get(0).asText(null);
            }
        }
        return null;
    }

    private static String toContentType(String format) {
        if (format == null || format.isBlank()) {
            return null;
        }
        String f = format.toLowerCase();
        if ("jpg".equals(f) || "jpeg".equals(f)) {
            return "image/jpeg";
        }
        if ("png".equals(f)) {
            return "image/png";
        }
        if ("webp".equals(f)) {
            return "image/webp";
        }
        if ("gif".equals(f)) {
            return "image/gif";
        }
        if ("ico".equals(f)) {
            return "image/x-icon";
        }
        return "image/" + f;
    }
}
