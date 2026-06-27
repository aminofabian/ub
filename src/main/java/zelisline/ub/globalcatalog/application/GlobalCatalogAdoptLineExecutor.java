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
 */
@Service
@RequiredArgsConstructor
public class GlobalCatalogAdoptLineExecutor {

    private final ItemCatalogService itemCatalogService;
    private final ItemRepository itemRepository;
    private final PricingService pricingService;
    private final InventoryLedgerService inventoryLedgerService;

    public record ImportLineCommand(
            String globalProductId,
            CreateItemRequest createReq,
            String branchId,
            BigDecimal sellingPrice,
            BigDecimal openingQty,
            BigDecimal openingUnitCost
    ) {
    }

    public record MergeLineCommand(
            String globalProductId,
            String existingItemId,
            String branchId,
            BigDecimal sellingPrice
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

        return new AdoptResultLineResponse(
                cmd.globalProductId(),
                "imported",
                item.getId(),
                item.getSku(),
                "Imported successfully");
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

        return new AdoptResultLineResponse(
                cmd.globalProductId(),
                "merged",
                existing.getId(),
                existing.getSku(),
                "Linked to existing product");
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
