package zelisline.ub.suppliers.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.application.PackageVariantStockResolver;
import zelisline.ub.catalog.application.ProductDisplayName;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.pricing.domain.BuyingPrice;
import zelisline.ub.pricing.repository.BuyingPriceRepository;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.suppliers.SupplierCodes;
import zelisline.ub.suppliers.api.dto.AddItemSupplierLinkRequest;
import zelisline.ub.suppliers.api.dto.ItemSupplierLinkResponse;
import zelisline.ub.suppliers.api.dto.SupplierItemLinkResponse;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.repository.BranchRepository;

@Service
@RequiredArgsConstructor
public class ItemSupplierLinkService {

    private final ItemRepository itemRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierProductRepository supplierProductRepository;
    private final SupplierProductPrimaryService primaryService;
    private final BuyingPriceRepository buyingPriceRepository;
    private final PackageVariantStockResolver packageVariantStockResolver;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final BranchRepository branchRepository;
    private final ObjectProvider<zelisline.ub.onboarding.progress.application.SetupProgressInvalidatePublisher>
            setupProgressInvalidate;
    private final ItemCatalogService itemCatalogService;
    private final SupplierPackOfferResolver supplierPackOfferResolver;
    private final SystemUnassignedLinkDemoter systemUnassignedLinkDemoter;

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
        return listLinksForSupplier(businessId, supplierId, null);
    }

    /**
     * @param branchId when set, {@code currentStock} is branch on-hand from active batches
     *                 (same source as catalog {@code stockQty} / admin stock edits). When null,
     *                 falls back to denormalized {@code item.currentStock}.
     */
    @Transactional(readOnly = true)
    public java.util.List<SupplierItemLinkResponse> listLinksForSupplier(
            String businessId,
            String supplierId,
            String branchId
    ) {
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

        // Resolve parent products for variants even when the parent itself is not linked.
        Set<String> missingParentIds = itemsById.values().stream()
                .map(Item::getVariantOfItemId)
                .filter(id -> id != null && !id.isBlank())
                .filter(id -> !itemsById.containsKey(id))
                .collect(Collectors.toSet());
        if (!missingParentIds.isEmpty()) {
            for (Item parent : itemRepository.findAllById(missingParentIds)) {
                if (businessId.equals(parent.getBusinessId()) && parent.getDeletedAt() == null) {
                    itemsById.put(parent.getId(), parent);
                }
            }
        }

        String stockBranch = blankToNull(branchId);
        Map<String, BigDecimal> branchStockByItemId = Map.of();
        if (stockBranch != null) {
            branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(stockBranch, businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
            Set<String> poolIds = new HashSet<>();
            for (Item item : itemsById.values()) {
                poolIds.addAll(packageVariantStockResolver.branchStockPoolItemIds(businessId, item));
            }
            if (!poolIds.isEmpty()) {
                Map<String, BigDecimal> raw = new HashMap<>();
                for (Object[] row : inventoryBatchRepository.sumQuantityRemainingForItemsAtBranch(
                        businessId, stockBranch, "active", poolIds)) {
                    raw.put((String) row[0], (BigDecimal) row[1]);
                }
                branchStockByItemId = raw;
            }
        }

        Map<String, BigDecimal> stockByItemId = branchStockByItemId;
        Map<String, String> thumbnailByItemId =
                itemCatalogService.resolveThumbnailUrls(businessId, itemsById.keySet());
        Map<String, List<SupplierPackOfferResolver.ResolvedPack>> packsByLinkId = supplierPackOfferResolver
                .resolveByLink(links.stream().collect(Collectors.toMap(SupplierProduct::getId, SupplierProduct::getItemId)));
        return links.stream()
                .map(sp -> {
                    Item item = itemsById.get(sp.getItemId());
                    Item parent = null;
                    if (item != null) {
                        String parentId = blankToNull(item.getVariantOfItemId());
                        if (parentId != null) {
                            parent = itemsById.get(parentId);
                        }
                    }
                    BigDecimal stock = null;
                    if (item != null && stockBranch != null) {
                        BigDecimal holderStock = packageVariantStockResolver.sumPoolStock(item, stockByItemId);
                        stock = packageVariantStockResolver.displayStockQty(item, holderStock);
                    } else if (item != null) {
                        stock = item.getCurrentStock();
                    }
                    String thumbnailUrl = resolveLinkThumbnail(item, parent, thumbnailByItemId);
                    return toSupplierItemLinkResponse(
                            sp, item, parent, stock, thumbnailUrl,
                            packsByLinkId.getOrDefault(sp.getId(), List.of()));
                })
                .toList();
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
            retireUnassignedLink(businessId, itemId, supplier);
            primaryService.normalizeAfterChange(businessId, itemId);
            maybeReactivateItem(item);
            notifySetupProgressChanged(businessId);
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
        retireUnassignedLink(businessId, itemId, supplier);
        primaryService.normalizeAfterChange(businessId, itemId);
        maybeReactivateItem(item);
        notifySetupProgressChanged(businessId);
        return toLinkResponse(sp, supplier);
    }

    /**
     * When a real supplier is linked, retire the synthetic "Unassigned (migrate)" link so the
     * item leaves the merchant-facing "Suppliers Not Linked" bucket. Linking to the synthetic
     * supplier itself is a no-op here. Shared demote logic lives in
     * {@link SystemUnassignedLinkDemoter} so it cannot drift from
     * {@code GlobalCatalogSupplierAdoptLinker}.
     */
    private void retireUnassignedLink(String businessId, String itemId, Supplier realSupplier) {
        if (realSupplier == null || SupplierCodes.SYSTEM_UNASSIGNED.equals(realSupplier.getCode())) {
            return;
        }
        String unassignedSupplierId = systemUnassignedLinkDemoter.demote(businessId, itemId);
        if (unassignedSupplierId != null) {
            migrateUnassignedBuyingPrices(businessId, itemId, unassignedSupplierId, realSupplier);
        }
    }

    /**
     * Re-point open-ended (active) buying prices from the synthetic unassigned supplier to the
     * newly linked real supplier. Only runs when the real supplier has no buying-price history
     * for the item yet, so real cost history is never clobbered; ambiguous cases stay on the
     * unassigned bucket and remain visible via the cost-issues surface.
     */
    private void migrateUnassignedBuyingPrices(
            String businessId,
            String itemId,
            String unassignedSupplierId,
            Supplier realSupplier
    ) {
        if (unassignedSupplierId == null || realSupplier == null) {
            return;
        }
        boolean realSupplierHasHistory = !buyingPriceRepository
                .findLatestRows(businessId, itemId, realSupplier.getId(), Pageable.ofSize(1))
                .isEmpty();
        if (realSupplierHasHistory) {
            return;
        }
        for (BuyingPrice bp : buyingPriceRepository.findOpenEnded(businessId, itemId, unassignedSupplierId)) {
            bp.setSupplierId(realSupplier.getId());
            String note = bp.getNotes() == null || bp.getNotes().isBlank()
                    ? "Moved from Suppliers Not Linked (SYS-UNASSIGNED) on supplier link"
                    : bp.getNotes() + " — moved from Suppliers Not Linked (SYS-UNASSIGNED) on supplier link";
            bp.setNotes(truncate(note, 2000));
            buyingPriceRepository.save(bp);
        }
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }

    private void notifySetupProgressChanged(String businessId) {
        var progress = setupProgressInvalidate.getIfAvailable();
        if (progress != null) {
            progress.invalidate(businessId);
        }
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
        Supplier supplier = supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(sp.getSupplierId(), businessId)
                .orElse(null);
        retireUnassignedLink(businessId, itemId, supplier);
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

    private ItemSupplierLinkResponse toLinkResponse(SupplierProduct sp, Supplier supplier) {
        String name = supplier != null ? supplier.getName() : "";
        List<ItemSupplierLinkResponse.ItemPackOfferPreview> packs = supplierPackOfferResolver
                .resolveByLink(Map.of(sp.getId(), sp.getItemId()))
                .getOrDefault(sp.getId(), List.of())
                .stream()
                .map(ItemSupplierLinkService::toPackOfferPreview)
                .toList();
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
                sp.getUpdatedAt(),
                packs
        );
    }

    private static ItemSupplierLinkResponse.ItemPackOfferPreview toPackOfferPreview(
            SupplierPackOfferResolver.ResolvedPack pack
    ) {
        return new ItemSupplierLinkResponse.ItemPackOfferPreview(
                pack.optionId(),
                pack.label(),
                pack.packUnit(),
                pack.unitsPerPack(),
                pack.unitPrice(),
                pack.eachPrice());
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

    private static String supplierLinkItemDisplayName(Item item, Item parent) {
        if (item == null) {
            return "";
        }
        String name = item.getName() != null ? item.getName().trim() : "Item";
        String variantOf = blankToNull(item.getVariantOfItemId());
        if (variantOf == null) {
            return name;
        }
        String family = parent != null && parent.getName() != null && !parent.getName().isBlank()
                ? parent.getName().trim()
                : name;
        String vn = item.getVariantName();
        if (vn != null && !vn.isBlank() && !isGenericVariantLabel(vn)) {
            return ProductDisplayName.join(family, vn);
        }
        String sku = item.getSku();
        if (sku != null && !sku.isBlank()) {
            return ProductDisplayName.withCode(family, sku);
        }
        return family;
    }

    private static String resolveLinkThumbnail(
            Item item,
            Item parent,
            Map<String, String> thumbnailByItemId
    ) {
        if (item != null) {
            String own = thumbnailByItemId.get(item.getId());
            if (own != null && !own.isBlank()) {
                return own;
            }
        }
        if (parent != null) {
            String parentThumb = thumbnailByItemId.get(parent.getId());
            if (parentThumb != null && !parentThumb.isBlank()) {
                return parentThumb;
            }
        }
        return null;
    }

    private SupplierItemLinkResponse toSupplierItemLinkResponse(
            SupplierProduct sp,
            Item item,
            Item parent,
            BigDecimal stock,
            String thumbnailUrl,
            List<SupplierPackOfferResolver.ResolvedPack> packs
    ) {
        String itemName = supplierLinkItemDisplayName(item, parent);
        String sku = item != null ? item.getSku() : "";
        String barcode = item != null && item.getBarcode() != null && !item.getBarcode().isBlank()
                ? item.getBarcode().trim()
                : null;
        BigDecimal catalogBuying = item != null ? item.getBuyingPrice() : null;
        BigDecimal catalogShelf = item != null ? item.getBundlePrice() : null;
        String variantOfItemId = item != null ? blankToNull(item.getVariantOfItemId()) : null;
        String parentItemName = null;
        String variantName = null;
        boolean packageVariant = item != null && item.isPackageVariant();
        if (variantOfItemId != null) {
            if (parent != null && parent.getName() != null && !parent.getName().isBlank()) {
                parentItemName = parent.getName().trim();
            }
            if (item.getVariantName() != null && !item.getVariantName().isBlank()) {
                variantName = item.getVariantName().trim();
            }
        }
        return new SupplierItemLinkResponse(
                sp.getId(),
                sp.getItemId(),
                itemName,
                sku,
                barcode,
                thumbnailUrl,
                stock,
                sp.isPrimaryLink(),
                sp.getSupplierSku(),
                sp.getDefaultCostPrice(),
                sp.getLastCostPrice(),
                catalogBuying,
                catalogShelf,
                sp.getPackSize(),
                sp.getPackUnit(),
                sp.isActive(),
                variantOfItemId,
                parentItemName,
                variantName,
                packageVariant,
                sp.getVersion(),
                sp.getCreatedAt(),
                sp.getUpdatedAt(),
                packs.stream().map(ItemSupplierLinkService::toPackOfferPreview).toList()
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
