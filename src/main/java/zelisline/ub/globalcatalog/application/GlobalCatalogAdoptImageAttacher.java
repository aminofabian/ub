package zelisline.ub.globalcatalog.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.api.dto.RegisterItemImageRequest;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.platform.media.CloudinaryImageService;
import zelisline.ub.platform.media.CloudinaryUploadResult;
import zelisline.ub.platform.media.MediaStore;

/**
 * Re-hosts a global catalog image into the tenant Cloudinary/local folder and
 * registers an {@code item_images} row with a <em>tenant-owned</em> {@code public_id}.
 *
 * <p>Never registers the shared {@code global-catalog/} public id on a tenant row —
 * tenant image delete would otherwise destroy the platform asset.
 */
@Service
@RequiredArgsConstructor
public class GlobalCatalogAdoptImageAttacher {

    private static final Logger log = LoggerFactory.getLogger(GlobalCatalogAdoptImageAttacher.class);

    private final MediaStore mediaStore;
    private final ItemCatalogService itemCatalogService;
    private final ItemRepository itemRepository;

    public record AttachResult(boolean attached, String warning) {
        public static AttachResult ok() {
            return new AttachResult(true, null);
        }

        public static AttachResult skipped(String warning) {
            return new AttachResult(false, warning);
        }
    }

    /**
     * @param onlyIfMissingCover when true (merge path), skip if the item already has a cover
     */
    public AttachResult attachFromGlobalUrl(
            String businessId,
            String itemId,
            String globalImageUrl,
            boolean onlyIfMissingCover
    ) {
        String url = blankToNull(globalImageUrl);
        if (url == null) {
            return AttachResult.skipped(null);
        }
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            return AttachResult.skipped("Global image URL is not portable http(s); skipped gallery registration");
        }

        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId).orElse(null);
        if (item == null) {
            return AttachResult.skipped("Item missing after adopt; could not attach image");
        }
        if (onlyIfMissingCover) {
            String existing = blankToNull(item.getImageKey());
            if (existing != null) {
                return AttachResult.skipped(null);
            }
        }

        if (!mediaStore.isConfigured()) {
            if (onlyIfMissingCover && blankToNull(item.getImageKey()) == null) {
                item.setImageKey(url);
                itemRepository.save(item);
            }
            return AttachResult.skipped(
                    "Cover set from global URL; gallery registration skipped (media store unavailable)");
        }

        try {
            String folder = CloudinaryImageService.folderItems(businessId, itemId);
            CloudinaryUploadResult uploaded = mediaStore.uploadFromRemoteUrl(url, folder);
            if (uploaded == null || blankToNull(uploaded.publicId()) == null || blankToNull(uploaded.secureUrl()) == null) {
                return AttachResult.skipped("Media re-host returned empty result; gallery not registered");
            }
            itemCatalogService.registerItemImage(
                    businessId,
                    itemId,
                    new RegisterItemImageRequest(
                            null,
                            null,
                            uploaded.width(),
                            uploaded.height(),
                            uploaded.contentType(),
                            item.getName(),
                            true,
                            uploaded.secureUrl(),
                            uploaded.publicId(),
                            uploaded.bytes(),
                            uploaded.format(),
                            uploaded.versionSignature(),
                            uploaded.predominantColorHex(),
                            uploaded.phash()
                    )
            );
            return AttachResult.ok();
        } catch (Exception ex) {
            log.warn(
                    "Global catalog image re-host failed businessId={} itemId={}: {}",
                    businessId,
                    itemId,
                    ex.toString());
            return AttachResult.skipped(
                    "Item imported; image gallery registration failed: "
                            + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
