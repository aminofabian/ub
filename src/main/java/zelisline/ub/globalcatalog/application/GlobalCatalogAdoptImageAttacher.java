package zelisline.ub.globalcatalog.application;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.api.dto.RegisterItemImageRequest;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.globalcatalog.domain.GlobalProductImage;
import zelisline.ub.platform.media.CloudinaryImageService;
import zelisline.ub.platform.media.CloudinaryUploadResult;
import zelisline.ub.platform.media.MediaStore;

/**
 * Re-hosts global catalog image(s) into the tenant Cloudinary/local folder and
 * registers {@code item_images} rows with <em>tenant-owned</em> {@code public_id}s.
 *
 * <p>Never registers shared {@code global-catalog/} public ids on tenant rows —
 * tenant image delete would otherwise destroy the platform asset.
 */
@Service
@RequiredArgsConstructor
public class GlobalCatalogAdoptImageAttacher {

    private static final Logger log = LoggerFactory.getLogger(GlobalCatalogAdoptImageAttacher.class);

    private final MediaStore mediaStore;
    private final ItemCatalogService itemCatalogService;
    private final ItemRepository itemRepository;
    private final GlobalProductImageGalleryService galleryService;

    public record AttachResult(boolean attached, int frameCount, String warning) {
        public static AttachResult ok(int frameCount) {
            return new AttachResult(true, frameCount, null);
        }

        public static AttachResult skipped(String warning) {
            return new AttachResult(false, 0, warning);
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
        List<String> urls = new ArrayList<>();
        if (blankToNull(globalImageUrl) != null) {
            urls.add(globalImageUrl.trim());
        }
        return attachGalleryUrls(businessId, itemId, urls, onlyIfMissingCover);
    }

    /**
     * Attaches the full global product gallery (falls back to cover URL when gallery empty).
     */
    public AttachResult attachGallery(
            String businessId,
            String itemId,
            String globalProductId,
            String coverImageUrl,
            boolean onlyIfMissingCover
    ) {
        List<String> urls = new ArrayList<>();
        if (blankToNull(globalProductId) != null) {
            for (GlobalProductImage frame : galleryService.listForProduct(globalProductId)) {
                String url = blankToNull(frame.getImageUrl());
                if (url != null) {
                    urls.add(url);
                }
                if (urls.size() >= GlobalProductImageGalleryService.MAX_GALLERY_IMAGES) {
                    break;
                }
            }
        }
        if (urls.isEmpty() && blankToNull(coverImageUrl) != null) {
            urls.add(coverImageUrl.trim());
        }
        return attachGalleryUrls(businessId, itemId, urls, onlyIfMissingCover);
    }

    private AttachResult attachGalleryUrls(
            String businessId,
            String itemId,
            List<String> globalImageUrls,
            boolean onlyIfMissingCover
    ) {
        List<String> urls = new ArrayList<>();
        for (String raw : globalImageUrls) {
            String url = blankToNull(raw);
            if (url == null) {
                continue;
            }
            if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                continue;
            }
            if (!urls.contains(url)) {
                urls.add(url);
            }
        }
        if (urls.isEmpty()) {
            return AttachResult.skipped(null);
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
            if (blankToNull(item.getImageKey()) == null) {
                item.setImageKey(urls.get(0));
                itemRepository.save(item);
            }
            return AttachResult.skipped(
                    "Cover set from global URL; gallery registration skipped (media store unavailable)");
        }

        int attached = 0;
        String lastWarning = null;
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            boolean primary = i == 0;
            try {
                String folder = CloudinaryImageService.folderItems(businessId, itemId);
                CloudinaryUploadResult uploaded = mediaStore.uploadFromRemoteUrl(url, folder);
                if (uploaded == null
                        || blankToNull(uploaded.publicId()) == null
                        || blankToNull(uploaded.secureUrl()) == null) {
                    lastWarning = "Media re-host returned empty result for gallery frame " + (i + 1);
                    continue;
                }
                itemCatalogService.registerItemImage(
                        businessId,
                        itemId,
                        new RegisterItemImageRequest(
                                null,
                                i,
                                uploaded.width(),
                                uploaded.height(),
                                uploaded.contentType(),
                                item.getName(),
                                primary,
                                uploaded.secureUrl(),
                                uploaded.publicId(),
                                uploaded.bytes(),
                                uploaded.format(),
                                uploaded.versionSignature(),
                                uploaded.predominantColorHex(),
                                uploaded.phash()
                        )
                );
                attached++;
            } catch (Exception ex) {
                log.warn(
                        "Global catalog gallery re-host failed businessId={} itemId={} frame={}: {}",
                        businessId,
                        itemId,
                        i,
                        ex.toString());
                lastWarning = "Image gallery frame " + (i + 1) + " failed: "
                        + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
            }
        }

        if (attached == 0) {
            return AttachResult.skipped(
                    lastWarning != null
                            ? lastWarning
                            : "Item imported; image gallery registration failed");
        }
        if (lastWarning != null && attached < urls.size()) {
            return new AttachResult(
                    true,
                    attached,
                    "Registered " + attached + "/" + urls.size() + " images; " + lastWarning);
        }
        return AttachResult.ok(attached);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
