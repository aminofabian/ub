package zelisline.ub.platform.media;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * One picture for one thing a shop sells that is not a catalogue product — a dish on a menu, a
 * service. Products and categories keep a gallery of their own; these keep a single picture on
 * their row.
 *
 * <p>What is uploaded is checked by its first bytes, not by its name or the type the browser
 * claims: only a JPEG, PNG or WebP picture is accepted. The media store enforces its own size
 * limit; this one is lower, because a menu picture is shown at phone size.
 */
@Component
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class PictureUploads {

    /** A phone photo, comfortably. The store resizes for display; this bounds what is kept. */
    static final int MAX_BYTES = 8 * 1024 * 1024;

    private final MediaStore mediaStore;

    /** @param folder where in the store it goes; must already be scoped to the business */
    public CloudinaryUploadResult upload(byte[] bytes, String originalFilename, String folder) {
        if (bytes == null || bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a picture to upload");
        }
        if (bytes.length > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "That picture is too large; use one under 8 MB");
        }
        if (!isPicture(bytes)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a JPEG, PNG or WebP picture");
        }
        if (!mediaStore.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Pictures cannot be saved right now");
        }
        try {
            return mediaStore.uploadImageToFolder(bytes, originalFilename, folder, false);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode().is4xxClientError()) {
                throw e;
            }
            // What went wrong at the store is ours to read in the log, not the shop owner's.
            log.warn("Picture upload to {} failed: {}", folder, e.getReason());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Pictures cannot be saved right now. Try again later.");
        } catch (RuntimeException e) {
            log.warn("Picture upload to {} failed: {}", folder, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Pictures cannot be saved right now. Try again later.");
        }
    }

    /** Removes a picture that is no longer used. Never fails the change that replaced it. */
    public void discard(String publicId) {
        if (publicId == null || publicId.isBlank() || !mediaStore.isConfigured()) {
            return;
        }
        try {
            mediaStore.destroyImage(publicId);
        } catch (RuntimeException ignored) {
            // An orphaned file in the store is harmless; a failed save of the menu is not.
        }
    }

    static boolean isPicture(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) {
            return true; // JPEG
        }
        if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0d && b[5] == 0x0a && b[6] == 0x1a && b[7] == 0x0a) {
            return true; // PNG
        }
        return b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P'; // WebP
    }
}
