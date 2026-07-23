package zelisline.ub.globalcatalog.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.globalcatalog.domain.GlobalProduct;
import zelisline.ub.globalcatalog.domain.GlobalProductImage;
import zelisline.ub.globalcatalog.repository.GlobalProductImageRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductRepository;
import zelisline.ub.platform.media.CloudinaryImageService;
import zelisline.ub.platform.media.CloudinaryUploadResult;
import zelisline.ub.platform.media.MediaStore;

/**
 * Manages {@code global_product_images} and keeps {@link GlobalProduct#getImageUrl()}
 * as the denormalized cover (sort order 0).
 */
@Service
@RequiredArgsConstructor
public class GlobalProductImageGalleryService {

    private static final Logger log = LoggerFactory.getLogger(GlobalProductImageGalleryService.class);

    /** Cap Cloudinary re-host cost per product on promote/adopt. */
    public static final int MAX_GALLERY_IMAGES = 10;

    private final GlobalProductImageRepository imageRepository;
    private final GlobalProductRepository productRepository;
    private final MediaStore mediaStore;

    public record GalleryFrame(
            String imageUrl,
            String imagePublicId,
            Integer width,
            Integer height,
            Long bytes,
            String format,
            String altText
    ) {
        public static GalleryFrame urlOnly(String imageUrl) {
            return new GalleryFrame(imageUrl, null, null, null, null, null, null);
        }
    }

    @Transactional(readOnly = true)
    public List<GlobalProductImage> listForProduct(String globalProductId) {
        return imageRepository.findByGlobalProductIdOrderBySortOrderAscIdAsc(globalProductId);
    }

    @Transactional(readOnly = true)
    public List<GlobalProductImage> listForProducts(java.util.Collection<String> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return List.of();
        }
        return imageRepository.findByGlobalProductIdInOrderBySortOrderAscIdAsc(productIds);
    }

    /**
     * Replaces the gallery from portable HTTPS source URLs (promote path).
     * Re-hosts when media store is configured; otherwise stores the source URLs.
     * Cover on {@code product} is updated to the first frame.
     *
     * @return number of frames that were re-hosted (or stored) successfully
     */
    @Transactional
    public int replaceFromSourceUrls(GlobalProduct product, List<String> sourceHttpsUrls) {
        List<String> urls = dedupeHttps(sourceHttpsUrls);
        List<GlobalProductImage> previous = imageRepository
                .findByGlobalProductIdOrderBySortOrderAscIdAsc(product.getId());
        imageRepository.deleteByGlobalProductId(product.getId());

        if (urls.isEmpty()) {
            product.setImageUrl(null);
            product.setImagePublicId(null);
            productRepository.save(product);
            destroyOrphans(previous, null);
            return 0;
        }

        List<GalleryFrame> frames = new ArrayList<>();
        int stored = 0;
        for (int i = 0; i < urls.size(); i++) {
            String sourceUrl = urls.get(i);
            GalleryFrame frame = rehostOrKeep(product.getId(), sourceUrl);
            if (frame == null) {
                continue;
            }
            frames.add(frame);
            stored++;
        }
        if (frames.isEmpty()) {
            // Keep previous cover if re-host failed entirely.
            if (!previous.isEmpty()) {
                for (GlobalProductImage row : previous) {
                    imageRepository.save(copyRow(product.getId(), row));
                }
                product.setImageUrl(previous.get(0).getImageUrl());
                product.setImagePublicId(previous.get(0).getImagePublicId());
                productRepository.save(product);
            }
            return 0;
        }

        persistFrames(product, frames);
        destroyOrphans(previous, frames.stream().map(GalleryFrame::imagePublicId).filter(Objects::nonNull).toList());
        return stored;
    }

    /**
     * Ensures the product cover is also gallery sort 0 (manual SA upload path).
     * Leaves other gallery frames intact.
     */
    @Transactional
    public void syncCoverAsPrimary(GlobalProduct product) {
        String coverUrl = blankToNull(product.getImageUrl());
        if (coverUrl == null) {
            return;
        }
        List<GlobalProductImage> existing = imageRepository
                .findByGlobalProductIdOrderBySortOrderAscIdAsc(product.getId());
        if (existing.isEmpty()) {
            GlobalProductImage row = new GlobalProductImage();
            row.setGlobalProductId(product.getId());
            row.setImageUrl(coverUrl);
            row.setImagePublicId(blankToNull(product.getImagePublicId()));
            row.setSortOrder(0);
            imageRepository.save(row);
            return;
        }
        GlobalProductImage primary = existing.get(0);
        primary.setImageUrl(coverUrl);
        primary.setImagePublicId(blankToNull(product.getImagePublicId()));
        primary.setSortOrder(0);
        imageRepository.save(primary);
    }

    @Transactional
    public void clearGallery(GlobalProduct product) {
        List<GlobalProductImage> previous = imageRepository
                .findByGlobalProductIdOrderBySortOrderAscIdAsc(product.getId());
        imageRepository.deleteByGlobalProductId(product.getId());
        destroyOrphans(previous, List.of());
    }

    private GalleryFrame rehostOrKeep(String productId, String sourceUrl) {
        if (!mediaStore.isConfigured()) {
            return GalleryFrame.urlOnly(sourceUrl);
        }
        try {
            String folder = CloudinaryImageService.folderGlobalCatalog(productId);
            CloudinaryUploadResult uploaded = mediaStore.uploadFromRemoteUrl(sourceUrl, folder);
            if (uploaded == null
                    || blankToNull(uploaded.publicId()) == null
                    || blankToNull(uploaded.secureUrl()) == null) {
                return GalleryFrame.urlOnly(sourceUrl);
            }
            return new GalleryFrame(
                    uploaded.secureUrl(),
                    uploaded.publicId(),
                    uploaded.width(),
                    uploaded.height(),
                    uploaded.bytes(),
                    uploaded.format(),
                    null);
        } catch (Exception ex) {
            log.warn("Gallery re-host failed for product {}: {}", productId, ex.toString());
            return GalleryFrame.urlOnly(sourceUrl);
        }
    }

    private void persistFrames(GlobalProduct product, List<GalleryFrame> frames) {
        int order = 0;
        for (GalleryFrame frame : frames) {
            GlobalProductImage row = new GlobalProductImage();
            row.setGlobalProductId(product.getId());
            row.setImageUrl(frame.imageUrl());
            row.setImagePublicId(blankToNull(frame.imagePublicId()));
            row.setSortOrder(order);
            row.setWidth(frame.width());
            row.setHeight(frame.height());
            row.setBytes(frame.bytes());
            row.setFormat(blankToNull(frame.format()));
            row.setAltText(blankToNull(frame.altText()));
            imageRepository.save(row);
            order++;
        }
        GalleryFrame cover = frames.get(0);
        product.setImageUrl(cover.imageUrl());
        product.setImagePublicId(blankToNull(cover.imagePublicId()));
        productRepository.save(product);
    }

    private static GlobalProductImage copyRow(String productId, GlobalProductImage source) {
        GlobalProductImage row = new GlobalProductImage();
        row.setGlobalProductId(productId);
        row.setImageUrl(source.getImageUrl());
        row.setImagePublicId(source.getImagePublicId());
        row.setSortOrder(source.getSortOrder());
        row.setAltText(source.getAltText());
        row.setWidth(source.getWidth());
        row.setHeight(source.getHeight());
        row.setBytes(source.getBytes());
        row.setFormat(source.getFormat());
        return row;
    }

    private void destroyOrphans(List<GlobalProductImage> previous, List<String> keepPublicIds) {
        if (!mediaStore.isConfigured() || previous.isEmpty()) {
            return;
        }
        java.util.Set<String> keep = keepPublicIds == null
                ? java.util.Set.of()
                : new java.util.HashSet<>(keepPublicIds);
        for (GlobalProductImage row : previous) {
            String publicId = blankToNull(row.getImagePublicId());
            if (publicId == null || keep.contains(publicId)) {
                continue;
            }
            try {
                mediaStore.destroyImage(publicId);
            } catch (Exception ignored) {
                // orphan ok
            }
        }
    }

    private static List<String> dedupeHttps(List<String> sourceHttpsUrls) {
        if (sourceHttpsUrls == null || sourceHttpsUrls.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String raw : sourceHttpsUrls) {
            String url = blankToNull(raw);
            if (url == null) {
                continue;
            }
            if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                continue;
            }
            unique.add(url);
            if (unique.size() >= MAX_GALLERY_IMAGES) {
                break;
            }
        }
        return List.copyOf(unique);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
