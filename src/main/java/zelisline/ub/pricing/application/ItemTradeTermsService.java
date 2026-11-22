package zelisline.ub.pricing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.pricing.api.dto.ItemPriceBreakDto;
import zelisline.ub.pricing.api.dto.ItemTradeTermsDto;
import zelisline.ub.pricing.domain.ItemPriceBreak;
import zelisline.ub.pricing.repository.ItemPriceBreakRepository;

/**
 * Wholesale terms on a shop product (ADR 0038, P1-5): a minimum order quantity and quantity-break
 * prices. They apply to online orders only; the till sells any quantity at the shelf price.
 */
@Service
@RequiredArgsConstructor
public class ItemTradeTermsService {

    public static final int MAX_BREAKS = 6;
    private static final int QTY_SCALE = 4;
    private static final int MONEY_SCALE = 2;

    private final ItemRepository itemRepository;
    private final ItemPriceBreakRepository priceBreakRepository;

    @Transactional(readOnly = true)
    public ItemTradeTermsDto get(String businessId, String itemId) {
        Item item = requireItem(businessId, itemId);
        return toDto(item, priceBreakRepository.findByBusinessIdAndItemIdOrderByMinQtyAsc(businessId, itemId));
    }

    @Transactional
    public ItemTradeTermsDto replace(String businessId, String itemId, ItemTradeTermsDto terms) {
        Item item = requireItem(businessId, itemId);
        BigDecimal minimum = normalizeQty(item, terms.minOrderQty(), "The minimum order");
        List<ItemPriceBreakDto> breaks = normalizeBreaks(item, terms.priceBreaks());

        item.setMinOrderQty(minimum);
        itemRepository.save(item);

        priceBreakRepository.deleteByBusinessIdAndItemId(businessId, itemId);
        priceBreakRepository.flush();
        for (ItemPriceBreakDto dto : breaks) {
            ItemPriceBreak row = new ItemPriceBreak();
            row.setBusinessId(businessId);
            row.setItemId(itemId);
            row.setMinQty(dto.minQty());
            row.setUnitPrice(dto.unitPrice());
            priceBreakRepository.save(row);
        }
        return new ItemTradeTermsDto(minimum, breaks);
    }

    /** Breaks for many products at once, lowest quantity first. Products with none are absent. */
    @Transactional(readOnly = true)
    public Map<String, List<ItemPriceBreakDto>> breaksByItem(String businessId, Collection<String> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<ItemPriceBreakDto>> out = new HashMap<>();
        for (ItemPriceBreak row : priceBreakRepository.findByBusinessIdAndItemIdInOrderByMinQtyAsc(businessId, itemIds)) {
            out.computeIfAbsent(row.getItemId(), k -> new ArrayList<>())
                    .add(new ItemPriceBreakDto(row.getMinQty(), row.getUnitPrice()));
        }
        return out;
    }

    /**
     * The unit price for this quantity: the lowest break the quantity reaches, when that is below
     * the price it would otherwise pay. A break never raises a price.
     */
    public static BigDecimal unitPriceFor(BigDecimal basePrice, BigDecimal quantity, List<ItemPriceBreakDto> breaks) {
        if (basePrice == null || quantity == null || breaks == null || breaks.isEmpty()) {
            return basePrice;
        }
        BigDecimal best = basePrice;
        for (ItemPriceBreakDto b : breaks) {
            if (quantity.compareTo(b.minQty()) >= 0 && b.unitPrice().compareTo(best) < 0) {
                best = b.unitPrice();
            }
        }
        return best;
    }

    private Item requireItem(String businessId, String itemId) {
        return itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    }

    private static BigDecimal normalizeQty(Item item, BigDecimal raw, String what) {
        if (raw == null) {
            return null;
        }
        BigDecimal qty = raw.setScale(QTY_SCALE, RoundingMode.HALF_UP);
        if (qty.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, what + " must be more than zero");
        }
        if (!item.isWeighed() && qty.stripTrailingZeros().scale() > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, what + " must be a whole number");
        }
        return qty;
    }

    private static List<ItemPriceBreakDto> normalizeBreaks(Item item, List<ItemPriceBreakDto> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() > MAX_BREAKS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "A product can have at most " + MAX_BREAKS + " quantity prices");
        }
        List<ItemPriceBreakDto> sorted = new ArrayList<>();
        for (ItemPriceBreakDto dto : raw) {
            BigDecimal qty = normalizeQty(item, dto.minQty(), "A quantity price");
            if (qty.compareTo(BigDecimal.ONE) <= 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "A quantity price must start above 1; the price for 1 is the shelf price");
            }
            sorted.add(new ItemPriceBreakDto(qty, dto.unitPrice().setScale(MONEY_SCALE, RoundingMode.HALF_UP)));
        }
        sorted.sort(Comparator.comparing(ItemPriceBreakDto::minQty));
        for (int i = 1; i < sorted.size(); i++) {
            ItemPriceBreakDto lower = sorted.get(i - 1);
            ItemPriceBreakDto higher = sorted.get(i);
            if (higher.minQty().compareTo(lower.minQty()) == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Two quantity prices start at the same quantity");
            }
            if (higher.unitPrice().compareTo(lower.unitPrice()) >= 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "The price must go down as the quantity goes up");
            }
        }
        return List.copyOf(sorted);
    }

    private static ItemTradeTermsDto toDto(Item item, List<ItemPriceBreak> rows) {
        return new ItemTradeTermsDto(
                item.getMinOrderQty(),
                rows.stream().map(r -> new ItemPriceBreakDto(r.getMinQty(), r.getUnitPrice())).toList());
    }
}
