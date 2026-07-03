package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.sales.api.dto.PosTopProductResponse;
import zelisline.ub.sales.repository.SaleItemRepository;

@Service
@RequiredArgsConstructor
public class PosTopProductsService {

    private final SaleItemRepository saleItemRepository;
    private final ItemRepository itemRepository;
    private final ItemCatalogService itemCatalogService;

    /**
     * Top items sold at the given branch, ranked by units sold (sum of
     * quantities) across completed, non-voided sales.
     */
    @Transactional(readOnly = true)
    public List<PosTopProductResponse> topProductsForBranch(
            String businessId,
            String branchId,
            String itemTypeId,
            int limit
    ) {
        if (businessId == null || businessId.isBlank()
                || branchId == null || branchId.isBlank()) {
            return List.of();
        }
        int bounded = Math.max(1, Math.min(limit, 100));
        String scopedType = itemTypeId != null && !itemTypeId.isBlank() ? itemTypeId.trim() : null;
        List<Object[]> rows = saleItemRepository.topItemsByUnitsSold(
                businessId,
                branchId,
                scopedType,
                PageRequest.of(0, bounded));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> itemIds = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            itemIds.add((String) row[0]);
        }
        Map<String, Item> itemsById = new LinkedHashMap<>();
        for (Item item : itemRepository.findAllById(itemIds)) {
            if (businessId.equals(item.getBusinessId())) {
                itemsById.put(item.getId(), item);
            }
        }
        Map<String, String> thumbs = itemCatalogService.resolveThumbnailUrls(businessId, itemIds);

        List<PosTopProductResponse> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            String itemId = (String) row[0];
            Item item = itemsById.get(itemId);
            if (item == null) {
                continue;
            }
            String name = item.getName() != null ? item.getName() : itemId;
            String sku = item.getSku();
            String thumb = thumbs.get(itemId);
            long saleCount = ((Number) row[1]).longValue();
            BigDecimal totalQty = row[2] instanceof BigDecimal bd
                    ? bd
                    : BigDecimal.valueOf(((Number) row[2]).doubleValue());
            Instant lastAt = (Instant) row[3];
            out.add(new PosTopProductResponse(
                    itemId,
                    name,
                    sku,
                    thumb,
                    saleCount,
                    totalQty,
                    lastAt
            ));
        }
        return out;
    }
}
