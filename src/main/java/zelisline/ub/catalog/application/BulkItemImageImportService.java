package zelisline.ub.catalog.application;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.catalog.api.dto.BulkItemImageImportResponse;
import zelisline.ub.catalog.api.dto.ItemImageResponse;
import zelisline.ub.catalog.api.dto.PatchItemRequest;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.integrations.csvimport.support.CsvImportReader;
import zelisline.ub.integrations.csvimport.support.CsvImportReader.SourceRow;
import zelisline.ub.platform.media.MediaStore;

/**
 * Bulk item-image import from a small CSV ({@code sku,image_url}) or a set of
 * SKU-named image files ({@code SKU-001.jpg}). Kept synchronous — image lists are
 * small (the missing-images page caps at a few hundred rows) and the response
 * reports per-row not-found / invalid issues inline.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BulkItemImageImportService {

    private static final int MAX_ROWS = 5_000;
    private static final int MAX_FILES = 100;
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final int MAX_IMAGE_KEY = 2048;
    private static final List<String> IMAGE_EXTENSIONS = List.of(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "avif", "bmp");

    private final ItemRepository itemRepository;
    private final ItemCatalogService itemCatalogService;
    private final MediaStore mediaStore;

    @Transactional
    public BulkItemImageImportResponse importImageUrls(String businessId, byte[] csvBytes, String actorUserId) {
        List<SourceRow> rows;
        try {
            rows = CsvImportReader.readRows(new java.io.ByteArrayInputStream(csvBytes));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not parse CSV: " + e.getMessage());
        }
        if (rows.size() > MAX_ROWS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Too many rows (" + rows.size() + "); max is " + MAX_ROWS);
        }
        int updated = 0;
        List<BulkItemImageImportResponse.RowIssue> notFound = new ArrayList<>();
        List<BulkItemImageImportResponse.RowIssue> invalid = new ArrayList<>();
        for (SourceRow sr : rows) {
            Map<String, String> c = sr.columns();
            String sku = c.getOrDefault("sku", "").trim();
            String imageUrl = c.getOrDefault("image_url",
                    c.getOrDefault("url", c.getOrDefault("image", ""))).trim();
            if (sku.isEmpty()) {
                invalid.add(new BulkItemImageImportResponse.RowIssue(sr.lineNumber(), "", "sku is required"));
                continue;
            }
            if (imageUrl.isEmpty() || imageUrl.length() > MAX_IMAGE_KEY) {
                invalid.add(new BulkItemImageImportResponse.RowIssue(
                        sr.lineNumber(), sku, "image_url must be non-blank and at most " + MAX_IMAGE_KEY + " chars"));
                continue;
            }
            Optional<Item> item = itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(businessId, sku);
            if (item.isEmpty()) {
                notFound.add(new BulkItemImageImportResponse.RowIssue(sr.lineNumber(), sku, "item sku not found"));
                continue;
            }
            itemCatalogService.patchItem(businessId, item.get().getId(), imageOnlyPatch(imageUrl), actorUserId);
            updated++;
        }
        return new BulkItemImageImportResponse(rows.size(), updated, notFound, invalid);
    }

    /**
     * Upload a batch of SKU-named image files (e.g. {@code SKU-001.jpg} → SKU {@code SKU-001}).
     * Each file is uploaded to the media store and registered as the item's cover + first
     * gallery image via {@link ItemCatalogService#uploadItemImageCloudinary}.
     */
    @Transactional
    public BulkItemImageImportResponse uploadImagesBySku(
            String businessId,
            List<MultipartFile> files
    ) {
        List<MultipartFile> safe = files == null ? List.of() : files;
        if (safe.size() > MAX_FILES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Too many files (" + safe.size() + "); max is " + MAX_FILES);
        }
        int updated = 0;
        List<BulkItemImageImportResponse.RowIssue> notFound = new ArrayList<>();
        List<BulkItemImageImportResponse.RowIssue> invalid = new ArrayList<>();
        // Media-store uploads happen before their DB rows are committed; if the batch
        // later rolls back, destroy the already-uploaded assets so nothing is orphaned.
        List<String> uploadedPublicIds = new ArrayList<>();
        registerRollbackCleanup(uploadedPublicIds);
        int index = 0;
        for (MultipartFile file : safe) {
            index++;
            String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().trim();
            String sku = skuFromFilename(filename);
            if (sku.isEmpty()) {
                invalid.add(new BulkItemImageImportResponse.RowIssue(
                        index, "", "filename does not include a SKU: " + filename));
                continue;
            }
            if (file.isEmpty() || file.getSize() > MAX_FILE_BYTES) {
                invalid.add(new BulkItemImageImportResponse.RowIssue(
                        index, sku, "file is empty or larger than 5 MB"));
                continue;
            }
            if (!looksLikeImage(file, filename)) {
                invalid.add(new BulkItemImageImportResponse.RowIssue(
                        index, sku, "not an image file: " + filename));
                continue;
            }
            Optional<Item> item = itemRepository.findByBusinessIdAndSkuIgnoreCaseAndDeletedAtIsNull(businessId, sku);
            if (item.isEmpty()) {
                notFound.add(new BulkItemImageImportResponse.RowIssue(index, sku, "item sku not found"));
                continue;
            }
            try {
                ItemImageResponse uploaded = itemCatalogService.uploadItemImageCloudinary(
                        businessId, item.get().getId(), file.getBytes(), filename, null, true);
                uploadedPublicIds.add(uploaded.publicId());
                updated++;
            } catch (IOException e) {
                invalid.add(new BulkItemImageImportResponse.RowIssue(index, sku, "could not read file"));
            } catch (ResponseStatusException ex) {
                invalid.add(new BulkItemImageImportResponse.RowIssue(
                        index, sku, ex.getReason() != null ? ex.getReason() : "upload failed"));
            }
        }
        return new BulkItemImageImportResponse(safe.size(), updated, notFound, invalid);
    }

    /**
     * On transaction rollback, best-effort destroy every asset uploaded by this batch so a
     * mid-batch failure never leaves unreferenced Cloudinary objects behind.
     */
    private void registerRollbackCleanup(List<String> uploadedPublicIds) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_ROLLED_BACK) {
                    return;
                }
                if (!mediaStore.isConfigured()) {
                    return;
                }
                for (String publicId : uploadedPublicIds) {
                    if (publicId == null || publicId.isBlank()) {
                        continue;
                    }
                    try {
                        mediaStore.destroyImage(publicId);
                    } catch (Exception ex) {
                        log.warn("Orphan cleanup failed for public_id={}: {}", publicId, ex.toString());
                    }
                }
            }
        });
    }

    /** SKU = filename minus its final extension; blank when there is no name. */
    private static String skuFromFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        String name = filename.trim();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return stem.trim();
    }

    private static boolean looksLikeImage(MultipartFile file, String filename) {
        String contentType = file.getContentType();
        if (contentType != null && !contentType.isBlank() && contentType.startsWith("image/")) {
            return true;
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return IMAGE_EXTENSIONS.contains(lower.substring(dot + 1));
    }

    /** {@link PatchItemRequest} with only {@code imageKey} set (all other fields null). */
    private static PatchItemRequest imageOnlyPatch(String imageKey) {
        return new PatchItemRequest(
                null, // expectedUpdatedAt
                null, // sku
                null, // barcode
                null, // name
                null, // description
                null, // categoryId
                null, // aisleId
                null, // itemTypeId
                null, // unitType
                null, // isWeighed
                null, // isSellable
                null, // isStocked
                null, // packageVariant
                null, // packagingUnitName
                null, // packagingUnitQty
                null, // bundleQty
                null, // bundlePrice
                null, // buyingPrice
                null, // bundleName
                null, // minStockLevel
                null, // reorderLevel
                null, // reorderQty
                null, // expiresAfterDays
                null, // hasExpiry
                imageKey,
                null, // active
                null, // webPublished
                null, // brand
                null, // size
                null, // variantName
                null  // pluCode
        );
    }
}
