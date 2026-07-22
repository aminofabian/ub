package zelisline.ub.platform.media;

/**
 * Image storage port — the seam between the cloud {@link CloudinaryImageService}
 * and the desktop {@code LocalMediaStore}.
 *
 * <p>The contract intentionally mirrors {@link CloudinaryImageService}'s existing
 * public methods so no call site has to change shape: the consumers
 * ({@code ItemCatalogService}, {@code TenancyService}, {@code CatalogTaxonomyService})
 * already depend on the cloud signature, and the desktop {@code LocalMediaStore}
 * returns a {@link CloudinaryUploadResult} populated with the fields it can
 * derive locally (publicId, secureUrl, bytes, format, contentType) — the
 * Cloudinary-specific {@code phash} / {@code predominantColorHex} /
 * {@code versionSignature} stay null. That keeps the persisted shape identical
 * and avoids a DB migration for the desktop SKU.
 *
 * <p>Exactly one bean implementing this interface is active at runtime:
 * <ul>
 *   <li>Cloud: {@link CloudinaryImageService}, gated on
 *       {@code app.media.cloudinary.enabled=true} (default).</li>
 *   <li>Desktop: {@code LocalMediaStore}, gated on
 *       {@code app.media.local.enabled=true} (set in
 *       {@code application-desktop.properties}).</li>
 * </ul>
 */
public interface MediaStore {

    /**
     * Whether the underlying store has the credentials / filesystem permissions
     * it needs. Consumers gate optional cleanup ({@link #destroyImage(String)})
     * on this so a misconfigured cloud install doesn't bring down sale flows.
     */
    boolean isConfigured();

    /** Upload an item / product image. The folder layout matches {@code CloudinaryImageService.folderItems}. */
    CloudinaryUploadResult uploadImage(byte[] fileBytes, String originalFilename, String businessId, String itemId);

    /** Upload to an arbitrary folder under the store root. Image fingerprinting is on by default. */
    CloudinaryUploadResult uploadImageToFolder(byte[] fileBytes, String originalFilename, String folderPath);

    /**
     * Upload to an arbitrary folder. {@code requestImageFingerprinting=false} skips
     * Cloudinary's phash/colors analysis — the parameter is ignored by stores
     * that don't generate those fields (e.g. the local desktop store).
     */
    CloudinaryUploadResult uploadImageToFolder(
            byte[] fileBytes,
            String originalFilename,
            String folderPath,
            boolean requestImageFingerprinting);

    /**
     * Re-host a remote image URL into {@code folderPath} without streaming bytes through the
     * application when the backend store supports remote fetch (Cloudinary). Local/desktop
     * stores download then write to disk.
     */
    CloudinaryUploadResult uploadFromRemoteUrl(String remoteUrl, String folderPath);

    /** Best-effort delete by {@code publicId}. Implementations swallow not-found errors. */
    void destroyImage(String publicId);
}
