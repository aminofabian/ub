package zelisline.ub.globalcatalog.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.globalcatalog.domain.GlobalProduct;
import zelisline.ub.globalcatalog.domain.GlobalProductPack;
import zelisline.ub.globalcatalog.domain.GlobalProductPackItem;
import zelisline.ub.globalcatalog.repository.GlobalProductPackItemRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductPackRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductRepository;

/**
 * Commits one CSV-import row in its own transaction (same pattern as
 * {@link SuperAdminGlobalCatalogPromoteLineExecutor}) so a single constraint or length
 * failure cannot mark the whole file rollback-only.
 *
 * <p>The parent link is applied <b>inside the child's row transaction</b> via
 * {@link GlobalCatalogProductGraphWriter} — never in a later best-effort pass. A crash
 * between rows can therefore leave a child unimported (reported), but never silently flat
 * (GLOBAL_CATALOG_IMPORT_SCOPE §4.1).
 */
@Service
@RequiredArgsConstructor
public class SuperAdminGlobalCatalogImportLineExecutor {

    private final GlobalProductRepository globalProductRepository;
    private final GlobalProductPackRepository globalProductPackRepository;
    private final GlobalProductPackItemRepository globalProductPackItemRepository;
    private final GlobalCatalogProductGraphWriter graphWriter;

    public record RowOutcome(String productId, boolean created) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RowOutcome apply(
            String catalogId,
            String existingProductId,
            String plannedIdForCreate,
            Map<ImportProductField, Object> values,
            boolean allowDraftParent
    ) {
        GlobalProduct product;
        boolean creating = existingProductId == null;
        if (creating) {
            product = new GlobalProduct();
            product.setCatalogId(catalogId);
            if (plannedIdForCreate != null && !plannedIdForCreate.isBlank()) {
                product.setId(plannedIdForCreate);
            }
        } else {
            product = globalProductRepository.findById(existingProductId)
                    .filter(p -> catalogId.equals(p.getCatalogId()))
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Product disappeared during import: " + existingProductId));
        }
        Object parentValue = null;
        boolean hasParentValue = false;
        for (Map.Entry<ImportProductField, Object> entry : values.entrySet()) {
            if (entry.getKey() == ImportProductField.VARIANT_OF) {
                parentValue = entry.getValue();
                hasParentValue = true;
                continue; // applied last, through the graph writer, with status already set
            }
            entry.getKey().apply(product, entry.getValue());
        }
        if (hasParentValue) {
            graphWriter.applyParent(product, (String) parentValue, allowDraftParent);
        }
        GlobalProduct saved = globalProductRepository.saveAndFlush(product);
        return new RowOutcome(saved.getId(), creating);
    }

    public record PackValues(
            String code,
            String name,
            String description,
            String storeKitId,
            String status,
            Integer sortOrder,
            boolean descriptionPresent,
            boolean storeKitPresent
    ) {
    }

    public record PackOutcome(String packId, boolean created, int membersAdded, int membersRemoved) {
    }

    /**
     * Upserts one pack and its membership in a single transaction. Additive by default;
     * {@code replace} swaps the full member list for the file's (§6.3.2 — the per-pack
     * cell is the only replace switch; there is no run-level flag).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PackOutcome applyPack(
            String catalogId,
            String existingPackId,
            PackValues values,
            boolean replace,
            List<String> memberProductIds
    ) {
        GlobalProductPack pack;
        boolean creating = existingPackId == null;
        if (creating) {
            pack = new GlobalProductPack();
            pack.setCatalogId(catalogId);
            pack.setCode(values.code());
        } else {
            pack = globalProductPackRepository.findByIdAndCatalogId(existingPackId, catalogId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Pack disappeared during import: " + existingPackId));
        }
        if (values.name() != null) {
            pack.setName(values.name());
        }
        if (values.descriptionPresent()) {
            pack.setDescription(values.description());
        }
        if (values.storeKitPresent()) {
            pack.setStoreKitId(values.storeKitId());
        }
        if (values.status() != null) {
            pack.setStatus(values.status());
        }
        if (values.sortOrder() != null) {
            pack.setSortOrder(values.sortOrder());
        }
        GlobalProductPack saved = globalProductPackRepository.saveAndFlush(pack);

        int added = 0;
        int removed = 0;
        List<String> current = globalProductPackItemRepository
                .findByPackIdOrderBySortOrderAsc(saved.getId())
                .stream()
                .map(GlobalProductPackItem::getGlobalProductId)
                .toList();
        Set<String> desired = new LinkedHashSet<>(memberProductIds);
        if (replace) {
            removed = (int) current.stream().filter(id -> !desired.contains(id)).count();
            globalProductPackItemRepository.deleteByPackId(saved.getId());
            int sort = 0;
            List<GlobalProductPackItem> items = new ArrayList<>();
            for (String productId : desired) {
                GlobalProductPackItem item = new GlobalProductPackItem();
                item.setPackId(saved.getId());
                item.setGlobalProductId(productId);
                item.setSortOrder(sort++);
                items.add(item);
            }
            globalProductPackItemRepository.saveAll(items);
            added = (int) desired.stream().filter(id -> !current.contains(id)).count();
        } else {
            Set<String> existing = new LinkedHashSet<>(current);
            int sort = current.size();
            List<GlobalProductPackItem> items = new ArrayList<>();
            for (String productId : desired) {
                if (existing.contains(productId)) {
                    continue;
                }
                GlobalProductPackItem item = new GlobalProductPackItem();
                item.setPackId(saved.getId());
                item.setGlobalProductId(productId);
                item.setSortOrder(sort++);
                items.add(item);
                added++;
            }
            globalProductPackItemRepository.saveAll(items);
        }
        globalProductPackItemRepository.flush();
        return new PackOutcome(saved.getId(), creating, added, removed);
    }
}
