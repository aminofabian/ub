package zelisline.ub.platform.media;

public record CloudinaryUploadResult(
        String publicId,
        String secureUrl,
        /** pixels */
        Integer width,
        Integer height,
        Long bytes,
        String format,
        String contentType,
        /** Provider asset version string (cache-bust / provenance). */
        String versionSignature,
        String predominantColorHex,
        String phash
) {
}
