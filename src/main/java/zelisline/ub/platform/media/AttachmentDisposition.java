package zelisline.ub.platform.media;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** {@code Content-Disposition} that makes a stored document download under the uploader's file name. */
final class AttachmentDisposition {

    private static final int MAX_NAME_LENGTH = 120;
    private static final String FALLBACK_BASENAME = "attachment";

    private AttachmentDisposition() {
    }

    static String download(String originalFilename, String extension) {
        String name = displayName(originalFilename, extension);
        String asciiName = name.replaceAll("[^A-Za-z0-9._ -]", "_");
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + asciiName + "\"; filename*=UTF-8''" + encoded;
    }

    private static String displayName(String originalFilename, String extension) {
        String base = originalFilename == null ? "" : originalFilename.replaceAll("[\\\\/\\p{Cntrl}\"]", "").trim();
        int dot = base.lastIndexOf('.');
        if (dot >= 0) {
            base = base.substring(0, dot).trim();
        }
        if (base.isEmpty()) {
            base = FALLBACK_BASENAME;
        }
        if (base.length() > MAX_NAME_LENGTH) {
            base = base.substring(0, MAX_NAME_LENGTH);
        }
        return base + "." + extension;
    }
}
