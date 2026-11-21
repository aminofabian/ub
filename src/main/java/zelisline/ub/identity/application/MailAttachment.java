package zelisline.ub.identity.application;

/**
 * A file riding along with an outbound email. Content stays in memory; callers
 * keep it small (a shift-close PDF, not a dump of the catalogue).
 */
public record MailAttachment(String filename, byte[] content, String mediaType) {

    public MailAttachment {
        if (filename == null || filename.isBlank()) {
            filename = "attachment.bin";
        }
        content = content == null ? new byte[0] : content;
        mediaType = mediaType == null || mediaType.isBlank() ? "application/octet-stream" : mediaType;
    }
}
