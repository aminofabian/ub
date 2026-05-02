package zelisline.ub.suppliers.application;

import java.time.Instant;
import java.util.Map;
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
                sp.isActive(),
                sp.getVersion(),
                sp.getCreatedAt(),
                sp.getUpdatedAt()
        );
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }
}
