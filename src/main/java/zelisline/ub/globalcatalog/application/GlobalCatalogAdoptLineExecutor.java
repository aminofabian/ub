package zelisline.ub.globalcatalog.application;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.api.dto.CreateVariantRequest;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.globalcatalog.api.dto.AdoptResultLineResponse;
import zelisline.ub.inventory.api.dto.PostOpeningBalanceRequest;
import zelisline.ub.inventory.application.InventoryLedgerService;
import zelisline.ub.pricing.api.dto.PostSellingPriceRequest;
import zelisline.ub.pricing.application.PricingService;

/**
 * Commits one global-catalog adopt line in its own transaction so a failure on one row
 * does not mark the whole batch rollback-only.
 *
 * <p>Image re-host runs in a <em>separate</em> {@code REQUIRES_NEW} transaction after
 * the item commit so Cloudinary latency cannot block pack adopts.
 */
@Service
@RequiredArgsConstructor
public class GlobalCatalogAdoptLineExecutor {

    private final ItemCatalogService itemCatalogService;
    private final ItemRepository itemRepository;
    private final PricingService pricingService;
    private final InventoryLedgerService inventoryLedgerService;
    private final GlobalCatalogAdoptImageAttacher imageAttacher;
    private final GlobalCatalogSupplierAdoptLinker supplierAdoptLinker;

    public record ImportLineCommand(
            String globalProductId,
            CreateItemRequest createReq,
            String branchId,
            BigDecimal sellingPrice,
            BigDecimal openingQty,
            BigDecimal openingUnitCost,
            String globalImageUrl
    ) {
    }

    public record ImportVariantLineCommand(
            String globalProductId,
            String parentItemId,
            CreateVariantRequest createReq,
            String branchId,
            BigDecimal sellingPrice,
            BigDecimal openingQty,
            BigDecimal openingUnitCost,
            BigDecimal buyingPriceFallback,
            String globalImageUrl
    ) {
    }

    public record MergeLineCommand(
            String globalProductId,
            String existingItemId,
            String branchId,
            BigDecimal sellingPrice,
            String globalImageUrl
    ) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AdoptResultLineResponse importLine(String businessId, ImportLineCommand cmd, String actorUserId) {
        var created = itemCatalogService.createItem(businessId, cmd.createReq(), null);
        Item item = itemRepository.findById(created.body().id())
                .orElseThrow(() -> new IllegalStateException("Created item not found"));
        item.setGlobalProductSourceId(cmd.globalProductId());
        itemRepository.saveAndFlush(item);

        applySellingPrice(businessId, item.getId(), cmd.branchId(), cmd.sellingPrice(), actorUserId, "Global catalog adopt");
        applyOpeningBalance(
                businessId,
                cmd.branchId(),
                item.getId(),
                cmd.openingQty(),
                cmd.openingUnitCost(),
                cmd.createReq().buyingPrice(),
                actorUserId
        );
        supplierAdoptLinker.linkPrimaryTemplate(businessId, cmd.globalProductId(), item);

        return new AdoptResultLineResponse(
                cmd.globalProductId(),
                "imported",
                item.getId(),
                item.getSku(),
                "Imported successfully");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AdoptResultLineResponse importVariantLine(
            String businessId,
            ImportVariantLineCommand cmd,
            String actorUserId
    ) {
        var created = itemCatalogService.createVariant(
                businessId, cmd.parentItemId(), cmd.createReq(), actorUserId);
        Item item = itemRepository.findById(created.id())
                .orElseThrow(() -> new IllegalStateException("Created variant not found"));
        item.setGlobalProductSourceId(cmd.globalProductId());
        itemRepository.saveAndFlush(item);

        applySellingPrice(
                businessId,
                item.getId(),
                cmd.branchId(),
                cmd.sellingPrice(),
                actorUserId,
                "Global catalog adopt");
        applyOpeningBalance(
                businessId,
                cmd.branchId(),
                item.getId(),
                cmd.openingQty(),
                cmd.openingUnitCost(),
                cmd.buyingPriceFallback(),
                actorUserId
        );
        supplierAdoptLinker.linkPrimaryTemplate(businessId, cmd.globalProductId(), item);

        return new AdoptResultLineResponse(
                cmd.globalProductId(),
                "imported",
                item.getId(),
                item.getSku(),
                "Imported as variant of parent");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AdoptResultLineResponse mergeLine(String businessId, MergeLineCommand cmd, String actorUserId) {
        Item existing = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(cmd.existingItemId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Existing product not found"));
        existing.setGlobalProductSourceId(cmd.globalProductId());
        itemRepository.saveAndFlush(existing);

        applySellingPrice(
                businessId,
                existing.getId(),
                cmd.branchId(),
                cmd.sellingPrice(),
                actorUserId,
                "Global catalog merge");
        supplierAdoptLinker.linkPrimaryTemplate(businessId, cmd.globalProductId(), existing);

        return new AdoptResultLineResponse(
                cmd.globalProductId(),
                "merged",
                existing.getId(),
                existing.getSku(),
                "Linked to existing product");
    }

    /**
     * Post-commit image attach. Non-fatal: returns the original line with an appended warning
     * when re-host/gallery registration fails.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AdoptResultLineResponse attachImageAfterCommit(
            String businessId,
            AdoptResultLineResponse line,
            String globalImageUrl,
            boolean onlyIfMissingCover
    ) {
        return attachGalleryAfterCommit(businessId, line, null, globalImageUrl, onlyIfMissingCover);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AdoptResultLineResponse attachGalleryAfterCommit(
            String businessId,
            AdoptResultLineResponse line,
            String globalProductId,
            String coverImageUrl,
            boolean onlyIfMissingCover
    ) {
        if (line == null || line.itemId() == null || line.itemId().isBlank()) {
            return line;
        }
        GlobalCatalogAdoptImageAttacher.AttachResult result = imageAttacher.attachGallery(
                businessId,
                line.itemId(),
                globalProductId,
                coverImageUrl,
                onlyIfMissingCover
        );
        if (result.warning() == null || result.warning().isBlank()) {
            if (result.attached()) {
                String note = result.frameCount() > 1
                        ? result.frameCount() + " images registered"
                        : "Image gallery registered";
                return new AdoptResultLineResponse(
                        line.globalProductId(),
                        line.status(),
                        line.itemId(),
                        line.sku(),
                        appendMessage(line.message(), note));
            }
            return line;
        }
        return new AdoptResultLineResponse(
                line.globalProductId(),
                line.status(),
                line.itemId(),
                line.sku(),
                appendMessage(line.message(), result.warning()));
    }

    private static String appendMessage(String base, String extra) {
        if (extra == null || extra.isBlank()) {
            return base;
        }
        if (base == null || base.isBlank()) {
            return extra;
        }
        return base + "; " + extra;
    }

    private void applySellingPrice(
            String businessId,
            String itemId,
            String branchId,
            BigDecimal sellingPrice,
            String actorUserId,
            String note
    ) {
        if (sellingPrice == null || sellingPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        pricingService.setSellingPrice(
                businessId,
                new PostSellingPriceRequest(itemId, branchId, sellingPrice, LocalDate.now(), note),
                actorUserId
        );
    }

    private void applyOpeningBalance(
            String businessId,
            String branchId,
            String itemId,
            BigDecimal openingQty,
            BigDecimal openingUnitCost,
            BigDecimal buyingPrice,
            String actorUserId
    ) {
        if (openingQty == null || openingQty.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal unitCost = openingUnitCost != null && openingUnitCost.compareTo(BigDecimal.ZERO) > 0
                ? openingUnitCost
                : (buyingPrice != null ? buyingPrice : BigDecimal.ONE);
        inventoryLedgerService.recordOpeningBalance(
                businessId,
                new PostOpeningBalanceRequest(branchId, itemId, openingQty, unitCost, "Global catalog opening stock"),
                actorUserId
        );
    }
}
