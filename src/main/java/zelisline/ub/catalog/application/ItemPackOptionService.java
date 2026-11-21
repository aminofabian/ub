package zelisline.ub.catalog.application;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.api.dto.CreateItemPackOptionRequest;
import zelisline.ub.catalog.api.dto.ItemPackOptionResponse;
import zelisline.ub.catalog.api.dto.PatchItemPackOptionRequest;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.domain.ItemPackOption;
import zelisline.ub.catalog.repository.ItemPackOptionRepository;
import zelisline.ub.catalog.repository.ItemRepository;

/**
 * CRUD for the pack shapes (12 / 18 / 48 …) offered on a catalog item.
 *
 * <p>Packs are tenant-scoped through the owning item ({@code business_id} matches
 * the item's tenancy). Unit purchase is not a row here.
 */
@Service
@RequiredArgsConstructor
public class ItemPackOptionService {

    private final ItemRepository itemRepository;
    private final ItemPackOptionRepository packOptionRepository;

    @Transactional(readOnly = true)
    public List<ItemPackOptionResponse> listPackOptions(String businessId, String itemId) {
        requireItem(businessId, itemId);
        return packOptionRepository.findByBusinessIdAndItemIdOrderBySortOrderAscIdAsc(businessId, itemId).stream()
                .map(ItemPackOptionResponse::from)
                .toList();
    }

    @Transactional
    public ItemPackOptionResponse createPackOption(
            String businessId,
            String itemId,
            CreateItemPackOptionRequest request
    ) {
        requireItem(businessId, itemId);
        validateUnitsPerPack(request.unitsPerPack());
        validateInnerPackaging(
                request.packUnit().trim(),
                request.unitsPerPack(),
                request.innerPackUnit(),
                request.innerPackCount(),
                request.baseUnitsPerInner());
        ensureUniqueShape(businessId, itemId, request.unitsPerPack(), request.packUnit().trim(), null);

        ItemPackOption option = new ItemPackOption();
        option.setBusinessId(businessId);
        option.setItemId(itemId);
        option.setLabel(blankToNull(request.label()));
        option.setPackUnit(request.packUnit().trim());
        option.setUnitsPerPack(request.unitsPerPack());
        option.setDefaultPackPrice(request.defaultPackPrice());
        option.setBarcode(blankToNull(request.barcode()));
        option.setSkuSuffix(blankToNull(request.skuSuffix()));
        option.setSortOrder(request.sortOrder() != null ? request.sortOrder() : 0);
        option.setActive(request.active() == null || request.active());
        option.setInnerPackUnit(blankToNull(request.innerPackUnit()));
        option.setInnerPackCount(request.innerPackCount());
        option.setBaseUnitsPerInner(request.baseUnitsPerInner());
        boolean defaultPurchase = Boolean.TRUE.equals(request.defaultPurchase());
        option.setDefaultPurchase(defaultPurchase);
        if (defaultPurchase) {
            clearOtherDefaults(businessId, itemId, option.getId());
        }
        try {
            return ItemPackOptionResponse.from(packOptionRepository.save(option));
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A pack option with the same units per pack and unit already exists");
        }
    }

    @Transactional
    public ItemPackOptionResponse patchPackOption(
            String businessId,
            String itemId,
            String optionId,
            PatchItemPackOptionRequest request
    ) {
        requireItem(businessId, itemId);
        ItemPackOption option = requireOption(businessId, itemId, optionId);

        if (request.label() != null) {
            option.setLabel(blankToNull(request.label()));
        }
        if (request.packUnit() != null) {
            option.setPackUnit(request.packUnit().trim());
        }
        if (request.unitsPerPack() != null) {
            validateUnitsPerPack(request.unitsPerPack());
            option.setUnitsPerPack(request.unitsPerPack());
        }
        if (request.defaultPackPrice() != null) {
            option.setDefaultPackPrice(request.defaultPackPrice());
        }
        if (request.barcode() != null) {
            option.setBarcode(blankToNull(request.barcode()));
        }
        if (request.skuSuffix() != null) {
            option.setSkuSuffix(blankToNull(request.skuSuffix()));
        }
        if (request.sortOrder() != null) {
            option.setSortOrder(request.sortOrder());
        }
        if (request.active() != null) {
            option.setActive(request.active());
        }
        if (request.innerPackUnit() != null) {
            if (blankToNull(request.innerPackUnit()) == null) {
                option.setInnerPackUnit(null);
                option.setInnerPackCount(null);
                option.setBaseUnitsPerInner(null);
            } else {
                option.setInnerPackUnit(blankToNull(request.innerPackUnit()));
            }
        }
        if (request.innerPackCount() != null) {
            option.setInnerPackCount(request.innerPackCount());
        }
        if (request.baseUnitsPerInner() != null) {
            option.setBaseUnitsPerInner(request.baseUnitsPerInner());
        }
        validateInnerPackaging(
                option.getPackUnit(),
                option.getUnitsPerPack(),
                option.getInnerPackUnit(),
                option.getInnerPackCount(),
                option.getBaseUnitsPerInner());
        if (Boolean.TRUE.equals(request.defaultPurchase())) {
            clearOtherDefaults(businessId, itemId, optionId);
            option.setDefaultPurchase(true);
        } else if (Boolean.FALSE.equals(request.defaultPurchase())) {
            option.setDefaultPurchase(false);
        }
        ensureUniqueShape(businessId, itemId, option.getUnitsPerPack(), option.getPackUnit(), optionId);
        try {
            return ItemPackOptionResponse.from(packOptionRepository.save(option));
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A pack option with the same units per pack and unit already exists");
        }
    }

    @Transactional
    public void deletePackOption(String businessId, String itemId, String optionId) {
        requireItem(businessId, itemId);
        ItemPackOption option = requireOption(businessId, itemId, optionId);
        packOptionRepository.delete(option);
    }

    private Item requireItem(String businessId, String itemId) {
        return itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
    }

    private ItemPackOption requireOption(String businessId, String itemId, String optionId) {
        return packOptionRepository.findByIdAndBusinessIdAndItemId(optionId, businessId, itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pack option not found"));
    }

    private static void validateUnitsPerPack(BigDecimal unitsPerPack) {
        if (unitsPerPack.compareTo(BigDecimal.ONE) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unitsPerPack must be greater than 1");
        }
    }

    /**
     * Inner packaging is optional and all-or-none: when present, the inner package count
     * multiplied by the base items per inner package must equal {@code unitsPerPack}.
     */
    private static void validateInnerPackaging(
            String packUnit,
            BigDecimal unitsPerPack,
            String innerPackUnitRaw,
            BigDecimal innerPackCount,
            BigDecimal baseUnitsPerInner
    ) {
        String innerPackUnit = blankToNull(innerPackUnitRaw);
        boolean anyPresent = innerPackUnit != null || innerPackCount != null || baseUnitsPerInner != null;
        if (!anyPresent) {
            return;
        }
        if (innerPackUnit == null || innerPackCount == null || baseUnitsPerInner == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Inner packaging needs a unit, how many inner packages one "
                            + packUnit + " holds, and how many items one inner package holds");
        }
        if (innerPackCount.compareTo(BigDecimal.ONE) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "innerPackCount must be greater than 1");
        }
        if (baseUnitsPerInner.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baseUnitsPerInner must be positive");
        }
        BigDecimal total = innerPackCount.multiply(baseUnitsPerInner).setScale(4, java.math.RoundingMode.HALF_UP);
        if (total.compareTo(unitsPerPack.setScale(4, java.math.RoundingMode.HALF_UP)) != 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Inner packaging does not add up: " + innerPackCount.stripTrailingZeros().toPlainString()
                            + " × " + baseUnitsPerInner.stripTrailingZeros().toPlainString()
                            + " = " + total.stripTrailingZeros().toPlainString()
                            + ", but one " + packUnit + " holds "
                            + unitsPerPack.stripTrailingZeros().toPlainString() + " base items");
        }
    }

    private void clearOtherDefaults(String businessId, String itemId, String excludedId) {
        for (ItemPackOption other : packOptionRepository
                .findByBusinessIdAndItemIdAndDefaultPurchaseTrue(businessId, itemId)) {
            if (!other.getId().equals(excludedId)) {
                other.setDefaultPurchase(false);
                packOptionRepository.save(other);
            }
        }
    }

    private void ensureUniqueShape(
            String businessId,
            String itemId,
            BigDecimal unitsPerPack,
            String packUnit,
            String excludedId
    ) {
        boolean duplicate = packOptionRepository.findByBusinessIdAndItemId(businessId, itemId).stream()
                .anyMatch(existing -> !existing.getId().equals(excludedId)
                        && existing.getUnitsPerPack().compareTo(unitsPerPack) == 0
                        && existing.getPackUnit().equalsIgnoreCase(packUnit));
        if (duplicate) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A pack option with the same units per pack and unit already exists");
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
