package zelisline.ub.platform.media;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import jakarta.annotation.PostConstruct;

/**
 * Filesystem-backed {@link MediaStore} for the desktop SKU
 * (see {@code DESKTOP_INSTALLATION.md} §5.3).
 *
 * <p>Stores uploaded images under {@code ${app.media.local.dir}/<folder>/<uuid>.<ext>}
 * and returns a {@link CloudinaryUploadResult} whose {@code secureUrl} is a
 * relative path under {@code /media/**}. The matching resource handler in
 * {@code DesktopWebConfig} serves the file back to the browser at the same
 * origin — no signed URLs, no remote CDN.
 *
 * <p>The {@code publicId} we persist is the relative path inside the media
 * root (e.g. {@code ub/abc/items/123/4a5e...png}); {@link #destroyImage(String)}
 * resolves it back to the on-disk file and deletes it.
 *
 * <p>Width / height are derived locally via {@link ImageIO} when the format is
 * recognised; Cloudinary-specific fields ({@code phash},
 * {@code predominantColorHex}, {@code versionSignature}) are intentionally left
 * {@code null}. Callers that genuinely require those (e.g. image-similarity
 * scans on the cloud SKU) are dormant on a desktop install anyway.
 */
@Service
@ConditionalOnProperty(name = "app.media.local.enabled", havingValue = "true")
public class LocalMediaStore implements MediaStore {

    private static final Logger log = LoggerFactory.getLogger(LocalMediaStore.class);
    private static final int MAX_UPLOAD_BYTES = 12 * 1024 * 1024;

    private final Path root;
    private final String publicBase;

    public LocalMediaStore(
            @Value("${app.media.local.dir:${user.home}/.palmart/media}") String rootDir,
            @Value("${app.media.local.public-base:/media}") String publicBase) {
        this.root = Path.of(rootDir).toAbsolutePath().normalize();
        this.publicBase = trimTrailingSlash(publicBase.isBlank() ? "/media" : publicBase);
    }

    @PostConstruct
    void ensureRootExists() {
        try {
            Files.createDirectories(root);
            log.info("[LocalMediaStore] active. root={} publicBase={}", root, publicBase);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot create local media root: " + root + " — " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isConfigured() {
        return Files.isDirectory(root) && Files.isWritable(root);
    }

    @Override
    public CloudinaryUploadResult uploadImage(
            byte[] fileBytes, String originalFilename, String businessId, String itemId) {
        return uploadImageToFolder(fileBytes, originalFilename,
                CloudinaryImageService.folderItems(businessId, itemId), true);
    }

    @Override
    public CloudinaryUploadResult uploadImageToFolder(
            byte[] fileBytes, String originalFilename, String folderPath) {
        return uploadImageToFolder(fileBytes, originalFilename, folderPath, true);
    }

    @Override
    public CloudinaryUploadResult uploadImageToFolder(
            byte[] fileBytes,
            String originalFilename,
            String folderPath,
            boolean requestImageFingerprinting) {
        validateBytes(fileBytes);

        String folder = folderPath == null || folderPath.isBlank() ? "ub/misc" : folderPath.trim();
        String safeName = sanitize(originalFilename);
        String ext = extensionOf(safeName);
        String filename = UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext);

        Path target = resolveSafely(folder, filename);
        try {
            Files.createDirectories(target.getParent());
            try (ByteArrayInputStream in = new ByteArrayInputStream(fileBytes)) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not write image to local media root: " + e.getMessage());
        }

        String publicId = folder + "/" + filename;
        String secureUrl = publicBase + "/" + publicId;
        String format = ext.isEmpty() ? null : ext.toLowerCase(Locale.ROOT);
        String contentType = toContentType(format);
        long bytes = (long) fileBytes.length;
        Integer width = null;
        Integer height = null;
        try {
            var img = ImageIO.read(target.toFile());
            if (img != null) {
                width = img.getWidth();
                height = img.getHeight();
            }
        } catch (IOException ignored) {
            // Non-image files (e.g. .ico variants) just leave width/height null.
        }

        return new CloudinaryUploadResult(
                publicId,
                secureUrl,
                width,
                height,
                bytes,
                format,
                contentType,
                null,
                null,
                null);
    }

    @Override
    public CloudinaryUploadResult uploadFromRemoteUrl(String remoteUrl, String folderPath) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty remote image URL");
        }
        String trimmed = remoteUrl.trim();
        if (!(trimmed.startsWith("http://") || trimmed.startsWith("https://"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Remote image URL must be http(s)");
        }
        byte[] bytes;
        try {
            bytes = java.net.URI.create(trimmed).toURL().openStream().readAllBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not download remote image: " + e.getMessage());
        }
        String name = filenameOf(trimmed);
        if (name.isBlank() || !name.contains(".")) {
            name = "remote.jpg";
        }
        return uploadImageToFolder(bytes, name, folderPath, true);
    }

    @Override
    public void destroyImage(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            return;
        }
        Path target;
        try {
            target = resolveSafely(parentOf(publicId), filenameOf(publicId));
        } catch (ResponseStatusException ignored) {
            // publicId pointed outside the media root — refuse silently to
            // avoid accidental cross-tenant deletion; log for diagnostics.
            log.warn("[LocalMediaStore] refused to destroy publicId outside root: {}", publicId);
            return;
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("[LocalMediaStore] failed to delete {}: {}", target, e.getMessage());
        }
    }

    // ---- helpers ----

    private void validateBytes(byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty image file");
        }
        if (fileBytes.length > MAX_UPLOAD_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Image exceeds size limit");
        }
    }

    /**
     * Joins {@code folder + filename} under {@link #root}, then asserts the
     * resolved path stays inside the root. This blocks {@code ../} traversal
     * via crafted folder strings (defence-in-depth — the cloud signature has
     * no such path, but desktop runs on the user's filesystem).
     */
    private Path resolveSafely(String folder, String filename) {
        Path target = root.resolve(folder).resolve(filename).normalize();
        if (!target.startsWith(root)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Resolved media path escapes the media root");
        }
        return target;
    }

    private static String sanitize(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "upload.bin";
        }
        return originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1);
    }

    private static String parentOf(String publicId) {
        int slash = publicId.lastIndexOf('/');
        return slash < 0 ? "" : publicId.substring(0, slash);
    }

    private static String filenameOf(String publicId) {
        int slash = publicId.lastIndexOf('/');
        return slash < 0 ? publicId : publicId.substring(slash + 1);
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String toContentType(String format) {
        if (format == null || format.isBlank()) {
            return null;
        }
        String f = format.toLowerCase(Locale.ROOT);
        return switch (f) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "ico" -> "image/x-icon";
            default -> "image/" + f;
        };
    }
}
