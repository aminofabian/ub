package zelisline.ub.platform.media;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Formats a store accepts, identified by file content rather than the client-supplied
 * name or Content-Type. Mirrors what Cloudinary's image upload accepted (including SVG
 * logos, ICO favicons and PDF receipts) so switching stores rejects nothing new.
 */
public enum MediaFormat {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    GIF("gif", "image/gif"),
    WEBP("webp", "image/webp"),
    BMP("bmp", "image/bmp"),
    ICO("ico", "image/x-icon"),
    TIFF("tiff", "image/tiff"),
    AVIF("avif", "image/avif"),
    HEIC("heic", "image/heic"),
    SVG("svg", "image/svg+xml"),
    PDF("pdf", "application/pdf");

    private static final int SVG_SNIFF_BYTES = 1024;
    private static final int FTYP_BOX_OFFSET = 4;
    private static final int FTYP_BRAND_OFFSET = 8;
    private static final Set<String> AVIF_BRANDS = Set.of("avif", "avis");
    private static final Set<String> HEIC_BRANDS = Set.of("heic", "heix", "heim", "heis", "hevc", "mif1", "msf1");

    private final String extension;
    private final String contentType;

    MediaFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    public static Optional<MediaFormat> detect(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return Optional.empty();
        }
        return Optional.ofNullable(detectBinary(bytes)).or(() -> detectText(bytes));
    }

    private static MediaFormat detectBinary(byte[] b) {
        if (startsWith(b, 0x89, 'P', 'N', 'G')) return PNG;
        if (startsWith(b, 0xFF, 0xD8, 0xFF)) return JPEG;
        if (startsWith(b, 'G', 'I', 'F', '8')) return GIF;
        if (startsWith(b, 'R', 'I', 'F', 'F') && ascii(b, 8, 4).equals("WEBP")) return WEBP;
        if (startsWith(b, '%', 'P', 'D', 'F')) return PDF;
        if (startsWith(b, 0x00, 0x00, 0x01, 0x00)) return ICO;
        if (startsWith(b, 'I', 'I', '*', 0x00) || startsWith(b, 'M', 'M', 0x00, '*')) return TIFF;
        if (startsWith(b, 'B', 'M')) return BMP;
        return detectIsoMedia(b);
    }

    private static MediaFormat detectIsoMedia(byte[] b) {
        if (!ascii(b, FTYP_BOX_OFFSET, 4).equals("ftyp")) return null;
        String brand = ascii(b, FTYP_BRAND_OFFSET, 4);
        if (AVIF_BRANDS.contains(brand)) return AVIF;
        if (HEIC_BRANDS.contains(brand)) return HEIC;
        return null;
    }

    private static Optional<MediaFormat> detectText(byte[] b) {
        String head = new String(Arrays.copyOf(b, Math.min(b.length, SVG_SNIFF_BYTES)), StandardCharsets.UTF_8)
                .replace("\uFEFF", "")
                .stripLeading()
                .toLowerCase(Locale.ROOT);
        boolean looksLikeSvg = head.startsWith("<") && head.contains("<svg");
        return looksLikeSvg ? Optional.of(SVG) : Optional.empty();
    }

    private static boolean startsWith(byte[] b, int... prefix) {
        if (b.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if ((b[i] & 0xFF) != prefix[i]) return false;
        }
        return true;
    }

    private static String ascii(byte[] b, int offset, int length) {
        if (b.length < offset + length) return "";
        return new String(b, offset, length, StandardCharsets.US_ASCII);
    }
}
