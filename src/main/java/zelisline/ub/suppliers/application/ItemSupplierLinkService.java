package zelisline.ub.suppliers.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.suppliers.api.dto.AddItemSupplierLinkRequest;
import zelisline.ub.suppliers.api.dto.ItemSupplierLinkResponse;
import zelisline.ub.suppliers.api.dto.SupplierItemLinkResponse;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;

@Service
@RequiredArgsConstructor
public class ItemSupplierLinkService {

    private final ItemRepository itemRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierProductRepository supplierProductRepository;
    private final SupplierProductPrimaryService primaryService;

    @Transactional(readOnly = true)
    public java.util.List<ItemSupplierLinkResponse> listLinks(String businessId, String itemId) {
        assertItemInBusiness(businessId, itemId);
        java.util.List<SupplierProduct> links = supplierProductRepository.listForItem(businessId, itemId);
        java.util.Set<String> ids = links.stream().map(SupplierProduct::getSupplierId).collect(Collectors.toSet());
        Map<String, Supplier> byId = supplierRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Supplier::getId, s -> s));
        return links.stream().map(sp -> toLinkResponse(sp, byId.get(sp.getSupplierId()))).toList();
    }

    @Transactional(readOnly = true)
    public java.util.List<SupplierItemLinkResponse> listLinksForSupplier(String businessId, String supplierId) {
        supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(supplierId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        java.util.List<SupplierProduct> links = supplierProductRepository.listForSupplier(businessId, supplierId);
        if (links.isEmpty()) {
            return java.util.List.of();
        }
        Set<String> itemIds = links.stream().map(SupplierProduct::getItemId).collect(Collectors.toSet());
        Map<String, Item> itemsById = itemRepository.findAllById(itemIds).stream()
                .filter(i -> businessId.equals(i.getBusinessId()) && i.getDeletedAt() == null)
                .collect(Collectors.toMap(Item::getId, i -> i, (a, b) -> a));
        return links.stream().map(sp -> toSupplierItemLinkResponse(sp, itemsById.get(sp.getItemId()))).toList();
    }

    @Transactional
    public ItemSupplierLinkResponse addLink(String businessId, String itemId, @Valid AddItemSupplierLinkRequest body) {
        Item item = assertItemInBusiness(businessId, itemId);
        Supplier supplier = supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(body.supplierId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        SupplierProduct sp = supplierProductRepository.findBySupplierIdAndItemId(supplier.getId(), itemId).orElse(null);
        if (sp == null) {
            SupplierProduct created = new SupplierProduct();
            created.setSupplierId(supplier.getId());
            created.setItemId(itemId);
            created.setSupplierSku(blankToNull(body.supplierSku()));
            created.setDefaultCostPrice(body.defaultCostPrice());
            applyPackFields(created, body.packSize(), body.packUnit());
            created.setActive(true);
            created.setDeletedAt(null);
            created.setPrimaryLink(Boolean.TRUE.equals(body.setPrimary()));
            try {
                supplierProductRepository.save(created);
            } catch (DataIntegrityViolationException ex) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Supplier link already exists", ex);
            }
            primaryService.normalizeAfterChange(businessId, itemId);
            maybeReactivateItem(item);
            return toLinkResponse(created, supplier);
        }
        if (sp.getDeletedAt() == null && sp.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Supplier already linked to this item");
        }
        sp.setDeletedAt(null);
        sp.setActive(true);
        sp.setSupplierSku(blankToNull(body.supplierSku()));
        if (body.defaultCostPrice() != null) {
            sp.setDefaultCostPrice(body.defaultCostPrice());
        }
        if (body.packSize() != null || body.packUnit() != null) {
            applyPackFields(sp, body.packSize(), body.packUnit());
        }
        if (Boolean.TRUE.equals(body.setPrimary())) {
            sp.setPrimaryLink(true);
        }
        supplierProductRepository.save(sp);
        primaryService.normalizeAfterChange(businessId, itemId);
        maybeReactivateItem(item);
        return toLinkResponse(sp, supplier);
    }

    @Transactional
    public void removeLink(String businessId, String itemId, String linkId) {
        Item item = assertItemInBusiness(businessId, itemId);
        SupplierProduct sp = supplierProductRepository.findLinkForBusiness(businessId, itemId, linkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier link not found"));
        Instant now = Instant.now();
        sp.setActive(false);
        sp.setPrimaryLink(false);
        sp.setDeletedAt(now);
        supplierProductRepository.save(sp);
        primaryService.normalizeAfterChange(businessId, itemId);
        deactivateItemIfOrphanedSellableStocked(item);
    }

    @Transactional
    public void setPrimaryLink(String businessId, String itemId, String linkId) {
        assertItemInBusiness(businessId, itemId);
        SupplierProduct sp = supplierProductRepository.findLinkForBusiness(businessId, itemId, linkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier link not found"));
        if (!sp.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Inactive link cannot be primary");
        }
        sp.setPrimaryLink(true);
        supplierProductRepository.save(sp);
        primaryService.normalizeAfterChange(businessId, itemId);
    }

    @Transactional
    public ItemSupplierLinkResponse patchLink(String businessId, String itemId, String linkId, zelisline.ub.suppliers.api.dto.PatchItemSupplierLinkRequest body) {
        assertItemInBusiness(businessId, itemId);
        SupplierProduct sp = supplierProductRepository.findLinkForBusiness(businessId, itemId, linkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier link not found"));
        if (body.supplierSku() != null) {
            sp.setSupplierSku(blankToNull(body.supplierSku()));
        }
        if (body.defaultCostPrice() != null) {
            sp.setDefaultCostPrice(body.defaultCostPrice());
        }
        if (body.packSize() != null || body.packUnit() != null) {
            applyPackFields(sp, body.packSize(), body.packUnit());
        }
        supplierProductRepository.save(sp);
        Supplier supplier = supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(sp.getSupplierId(), businessId)
                .orElse(null);
        return toLinkResponse(sp, supplier);
    }

    private Item assertItemInBusiness(String businessId, String itemId) {
        return itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
    }

    private void deactivateItemIfOrphanedSellableStocked(Item item) {
        if (!item.isSellable() || !item.isStocked()) {
            return;
        }
        if (supplierProductRepository.existsActiveByItemId(item.getId())) {
            return;
        }
        item.setActive(false);
        itemRepository.save(item);
    }

    private void maybeReactivateItem(Item item) {
        if (!item.isActive() && item.isSellable() && item.isStocked()) {
            item.setActive(true);
            itemRepository.save(item);
        }
    }

    private static ItemSupplierLinkResponse toLinkResponse(SupplierProduct sp, Supplier supplier) {
        String name = supplier != null ? supplier.getName() : "";
        return new ItemSupplierLinkResponse(
                sp.getId(),
                sp.getSupplierId(),
                name,
                sp.isPrimaryLink(),
                sp.getSupplierSku(),
                sp.getDefaultCostPrice(),
                sp.getLastCostPrice(),
                sp.getPackSize(),
                sp.getPackUnit(),
                sp.isActive(),
                sp.getLastPurchaseAt(),
                sp.getVersion(),
                sp.getCreatedAt(),
                sp.getUpdatedAt()
        );
    }

    /**
     * Matches frontend {@code itemCatalogDisplayTitle}: parent name plus variant label, or SKU when variant label is absent
     * or a placeholder string from imports (e.g. {@code "Variant"}).
     */
    private static boolean isGenericVariantLabel(String variantName) {
        if (variantName == null || variantName.isBlank()) {
            return true;
        }
        String t = variantName.trim().toLowerCase(Locale.ROOT);
        return t.equals("variant")
                || t.equals("option")
                || t.equals("variation")
                || t.equals("default");
    }

    private static String supplierLinkItemDisplayName(Item item) {
        if (item == null) {
            return "";
        }
        String name = item.getName() != null ? item.getName().trim() : "Item";
        String variantOf = item.getVariantOfItemId();
        if (variantOf == null || variantOf.isBlank()) {
            return name;
        }
        String vn = item.getVariantName();
        if (vn != null && !vn.isBlank() && !isGenericVariantLabel(vn)) {
            return name + " · " + vn.trim();
        }
        String sku = item.getSku();
        if (sku != null && !sku.isBlank()) {
            return name + " · " + sku.trim();
        }
        return name;
    }

    private static SupplierItemLinkResponse toSupplierItemLinkResponse(SupplierProduct sp, Item item) {
        String itemName = supplierLinkItemDisplayName(item);
        String sku = item != null ? item.getSku() : "";
        String barcode = item != null && item.getBarcode() != null && !item.getBarcode().isBlank()
                ? item.getBarcode().trim()
                : null;
        BigDecimal stock = item != null ? item.getCurrentStock() : null;
        return new SupplierItemLinkResponse(
                sp.getId(),
                sp.getItemId(),
                itemName,
                sku,
                barcode,
                stock,
                sp.isPrimaryLink(),
                sp.getSupplierSku(),
                sp.getDefaultCostPrice(),
                sp.getLastCostPrice(),
                sp.getPackSize(),
                sp.getPackUnit(),
                sp.isActive(),
                sp.getVersion(),
                sp.getCreatedAt(),
                sp.getUpdatedAt()
        );
    }

    private static void applyPackFields(SupplierProduct sp, BigDecimal packSize, String packUnit) {
        if (packSize != null) {
            if (packSize.signum() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "packSize must be positive");
            }
            sp.setPackSize(packSize);
        }
        if (packUnit != null) {
            String unit = blankToNull(packUnit);
            sp.setPackUnit(unit);
            if (unit == null) {
                sp.setPackSize(null);
            }
        }
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }
}
