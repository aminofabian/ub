package zelisline.ub.inventory.application;

import java.math.BigDecimal;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.inventory.api.dto.ItemStockThresholdsResponse;
import zelisline.ub.inventory.api.dto.PatchItemStockThresholdsRequest;

@Service
@RequiredArgsConstructor
public class ItemStockThresholdsService {

    private final ItemRepository itemRepository;

    @Transactional
    public ItemStockThresholdsResponse patch(
            String businessId,
            String itemId,
            PatchItemStockThresholdsRequest body,
            boolean canWritePar
    ) {
        if (body == null
                || (body.minStockLevel() == null
                        && body.reorderLevel() == null
                        && body.reorderQty() == null)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Provide minStockLevel, reorderLevel and/or reorderQty"
            );
        }
        Item item = itemRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Item not found"));

        if (body.minStockLevel() != null) {
            item.setMinStockLevel(body.minStockLevel());
        }
        if (body.reorderLevel() != null) {
            item.setReorderLevel(body.reorderLevel());
        }
        // Keep both in sync when only one is sent (grocery counter UX).
        if (body.minStockLevel() != null && body.reorderLevel() == null) {
            item.setReorderLevel(body.minStockLevel());
        } else if (body.reorderLevel() != null && body.minStockLevel() == null) {
            item.setMinStockLevel(body.reorderLevel());
        }
        // Par ("order up to") is independent — no sync with min / reorder.
        // Only callers with the par-level permission may set it.
        if (body.reorderQty() != null) {
            if (!canWritePar) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Setting order-up-to (par) is not enabled for your role"
                );
            }
            item.setReorderQty(body.reorderQty());
        }

        itemRepository.save(item);
        BigDecimal min = item.getMinStockLevel();
        BigDecimal reorder = item.getReorderLevel();
        BigDecimal par = item.getReorderQty();
        return new ItemStockThresholdsResponse(item.getId(), min, reorder, par);
    }
}
