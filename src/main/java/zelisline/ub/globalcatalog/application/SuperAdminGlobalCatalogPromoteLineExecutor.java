package zelisline.ub.globalcatalog.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.globalcatalog.domain.GlobalProduct;
import zelisline.ub.globalcatalog.repository.GlobalProductRepository;

/**
 * Commits one promote upsert (and optional image re-host) in its own transaction so a
 * single constraint / Cloudinary failure cannot mark the whole batch rollback-only and
 * wipe earlier rows after a "clear catalog" archive.
 */
@Service
@RequiredArgsConstructor
public class SuperAdminGlobalCatalogPromoteLineExecutor {

    private final GlobalProductRepository globalProductRepository;
    private final GlobalProductImageGalleryService galleryService;

    public record SavedProduct(GlobalProduct product, boolean created) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SavedProduct saveProduct(GlobalProduct product, boolean created) {
        GlobalProduct saved = globalProductRepository.saveAndFlush(product);
        return new SavedProduct(saved, created);
    }

    /** Updates (or clears) the parent link after both parent and child exist in global. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateVariantParent(String productId, String variantOfGlobalProductId) {
        if (productId == null || productId.isBlank()) {
            return;
        }
        GlobalProduct product = globalProductRepository.findById(productId).orElse(null);
        if (product == null) {
            return;
        }
        String next = variantOfGlobalProductId == null || variantOfGlobalProductId.isBlank()
                ? null
                : variantOfGlobalProductId.trim();
        if (next != null && next.equals(productId)) {
            next = null;
        }
        String current = product.getVariantOfGlobalProductId();
        if (current == null ? next == null : current.equals(next)) {
            return;
        }
        product.setVariantOfGlobalProductId(next);
        globalProductRepository.saveAndFlush(product);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean rehostImages(String productId, List<String> sourceHttpsUrls) {
        if (productId == null || sourceHttpsUrls == null || sourceHttpsUrls.isEmpty()) {
            return false;
        }
        GlobalProduct product = globalProductRepository.findById(productId).orElse(null);
        if (product == null) {
            return false;
        }
        return galleryService.replaceFromSourceUrls(product, sourceHttpsUrls) > 0;
    }
}
