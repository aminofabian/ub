package zelisline.ub.globalcatalog.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.globalcatalog.api.dto.AdoptLineRequest;
import zelisline.ub.globalcatalog.api.dto.AdoptRequest;
import zelisline.ub.globalcatalog.api.dto.AdoptResponse;
import zelisline.ub.globalcatalog.api.dto.ReplaceCatalogEligibilityResponse;
import zelisline.ub.globalcatalog.api.dto.ReplaceCatalogRequest;
import zelisline.ub.globalcatalog.api.dto.ReplaceCatalogResponse;
import zelisline.ub.globalcatalog.domain.GlobalCatalog;
import zelisline.ub.globalcatalog.domain.GlobalProductPack;
import zelisline.ub.globalcatalog.domain.GlobalProductStatus;
import zelisline.ub.globalcatalog.repository.GlobalProductPackRepository;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Empty-shop catalog replace: soft-delete active items, then adopt a starter pack.
 *
 * <p>Blocked when the business has any sales history or any inventory batch with
 * {@code quantityRemaining > 0}. Soft-deleted SKUs remain reserved, so adopt uses
 * fresh SKUs from the pack templates / generated names.
 */
@Service
@RequiredArgsConstructor
public class GlobalCatalogReplaceService {

    private static final String STATUS_PUBLISHED = GlobalProductStatus.PUBLISHED;

    private final BusinessRepository businessRepository;
    private final GlobalProductPackRepository globalProductPackRepository;
    private final ItemRepository itemRepository;
    private final ItemCatalogService itemCatalogService;
    private final GlobalCatalogService globalCatalogService;
    private final SaleRepository saleRepository;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final GlobalCatalogResolver globalCatalogResolver;

    @Transactional(readOnly = true)
    public ReplaceCatalogEligibilityResponse preview(String businessId, String packId) {
        requireBusiness(businessId);
        GlobalProductPack pack = requirePublishedPack(businessId, packId);
        long activeItems = itemRepository.findByBusinessIdAndDeletedAtIsNull(businessId).size();
        boolean hasSales = saleRepository.existsByBusinessId(businessId);
        boolean hasNonZeroBatches = inventoryBatchRepository
                .existsByBusinessIdAndQuantityRemainingGreaterThan(businessId, java.math.BigDecimal.ZERO);
        String blockReason = blockReason(hasSales, hasNonZeroBatches);
        int packProductCount = globalProductPackRepository.findProductIdsByPackId(pack.getId()).size();
        return new ReplaceCatalogEligibilityResponse(
                blockReason == null,
                blockReason,
                activeItems,
                hasSales,
                hasNonZeroBatches,
                pack.getId(),
                pack.getName(),
                packProductCount);
    }

    @Transactional
    public ReplaceCatalogResponse replace(String businessId, ReplaceCatalogRequest request, String actorUserId) {
        requireBusiness(businessId);
        GlobalProductPack pack = requirePublishedPack(businessId, request.packId());

        boolean hasSales = saleRepository.existsByBusinessId(businessId);
        boolean hasNonZeroBatches = inventoryBatchRepository
                .existsByBusinessIdAndQuantityRemainingGreaterThan(businessId, java.math.BigDecimal.ZERO);
        String blockReason = blockReason(hasSales, hasNonZeroBatches);
        if (blockReason != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, blockReason);
        }

        int softDeleted = itemCatalogService.softDeleteAllActiveItems(businessId, actorUserId);
        List<AdoptLineRequest> lines = buildPackLines(pack.getId());
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Starter pack has no products");
        }

        AdoptResponse adopt = globalCatalogService.adopt(
                businessId,
                new AdoptRequest(request.openingBranchId(), lines, true, request.packId()),
                actorUserId);
        return new ReplaceCatalogResponse(softDeleted, adopt);
    }

    private List<AdoptLineRequest> buildPackLines(String packId) {
        List<String> productIds = globalProductPackRepository.findProductIdsByPackId(packId);
        List<AdoptLineRequest> lines = new ArrayList<>(productIds.size());
        for (String productId : productIds) {
            lines.add(new AdoptLineRequest(
                    productId,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));
        }
        return lines;
    }

    private GlobalProductPack requirePublishedPack(String businessId, String packId) {
        GlobalCatalog catalog = globalCatalogResolver.resolveForBusiness(businessId);
        return globalProductPackRepository.findByIdAndCatalogId(packId, catalog.getId())
                .filter(p -> STATUS_PUBLISHED.equals(p.getStatus()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Starter pack not found"));
    }

    private void requireBusiness(String businessId) {
        if (!businessRepository.existsById(businessId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found");
        }
    }

    private static String blockReason(boolean hasSales, boolean hasNonZeroBatches) {
        if (hasSales) {
            return "Cannot replace catalogue: this shop already has sales history";
        }
        if (hasNonZeroBatches) {
            return "Cannot replace catalogue: clear all stock (non-zero batches) first";
        }
        return null;
    }
}
