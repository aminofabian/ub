package zelisline.ub.platform.media;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Support chat attachment types. Images and PDFs are recognised by content; Office files by
 * their container signature plus extension; CSV/TXT must decode as text. Anything else,
 * including SVG and HTML, is refused so the bucket never serves active content.
 */
public enum AttachmentFormat {
    PNG("png", "image/png"),
    JPEG("jpg", "image/jpeg"),
    GIF("gif", "image/gif"),
    WEBP("webp", "image/webp"),
    PDF("pdf", "application/pdf"),
    CSV("csv", "text/csv"),
    TXT("txt", "text/plain"),
    XLS("xls", "application/vnd.ms-excel"),
    DOC("doc", "application/msword"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private static final int TEXT_SNIFF_BYTES = 8192;
    private static final int[] OLE_SIGNATURE = {0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1};
    private static final int[] ZIP_SIGNATURE = {'P', 'K', 0x03, 0x04};
    private static final Map<MediaFormat, AttachmentFormat> BY_MEDIA_FORMAT = Map.of(
            MediaFormat.PNG, PNG,
            MediaFormat.JPEG, JPEG,
            MediaFormat.GIF, GIF,
            MediaFormat.WEBP, WEBP,
            MediaFormat.PDF, PDF);

    private final String extension;
    private final String contentType;

    AttachmentFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    public boolean isImage() {
        return contentType.startsWith("image/");
    }

    public static Optional<AttachmentFormat> detect(byte[] bytes, String filename) {
        if (bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        Optional<MediaFormat> media = MediaFormat.detect(bytes);
        if (media.isPresent()) {
            return Optional.ofNullable(BY_MEDIA_FORMAT.get(media.get()));
        }
        String ext = extensionOf(filename);
        if (startsWith(bytes, OLE_SIGNATURE)) return oneOf(ext, XLS, DOC);
        if (startsWith(bytes, ZIP_SIGNATURE)) return oneOf(ext, XLSX, DOCX);
        return isText(bytes) ? oneOf(ext, CSV, TXT) : Optional.empty();
    }

    private static Optional<AttachmentFormat> oneOf(String ext, AttachmentFormat... candidates) {
        return Arrays.stream(candidates).filter(f -> f.extension.equals(ext)).findFirst();
    }

    private static String extensionOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isText(byte[] bytes) {
        byte[] head = Arrays.copyOf(bytes, Math.min(bytes.length, TEXT_SNIFF_BYTES));
        for (byte b : head) {
            if (b == 0) return false;
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(trimPartialCodePoint(head, bytes.length)));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /** A sniff window can cut a multi-byte UTF-8 character in half; drop that tail. */
    private static byte[] trimPartialCodePoint(byte[] head, int totalLength) {
        if (head.length == totalLength) return head;
        int end = head.length;
        int continuation = 0;
        while (end > 0 && continuation < 3 && (head[end - 1] & 0xC0) == 0x80) {
            end--;
            continuation++;
        }
        if (end > 0 && (head[end - 1] & 0xC0) == 0xC0) end--;
        return Arrays.copyOf(head, end);
    }

    private static boolean startsWith(byte[] b, int[] prefix) {
        if (b.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if ((b[i] & 0xFF) != prefix[i]) return false;
        }
        return true;
    }
}
