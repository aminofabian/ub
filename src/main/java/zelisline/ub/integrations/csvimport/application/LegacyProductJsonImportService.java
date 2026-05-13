package zelisline.ub.integrations.csvimport.application;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.api.dto.CreateAisleRequest;
import zelisline.ub.catalog.api.dto.CreateCategoryRequest;
import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.api.dto.CreateVariantRequest;
import zelisline.ub.catalog.api.dto.CategoryResponse;
import zelisline.ub.catalog.api.dto.ItemResponse;
import zelisline.ub.catalog.api.dto.PatchItemRequest;
import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.application.CatalogTaxonomyService;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.domain.Aisle;
import zelisline.ub.catalog.domain.ItemType;
import zelisline.ub.catalog.repository.AisleRepository;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.integrations.csvimport.api.dto.CsvImportLineError;
import zelisline.ub.integrations.csvimport.api.dto.CsvImportResponse;
import zelisline.ub.inventory.api.dto.PostOpeningBalanceRequest;
import zelisline.ub.inventory.application.InventoryLedgerService;
import zelisline.ub.pricing.api.dto.PostSellingPriceRequest;
import zelisline.ub.pricing.application.PricingService;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.repository.BranchRepository;

/**
 * Imports a legacy JSON export (array or {@code { "products": [...] }}) into catalog + selling prices
 * + optional opening stock on a branch.
 */
@Service
@RequiredArgsConstructor
public class LegacyProductJsonImportService {

    private static final Pattern UUID_REGEX = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final BigDecimal QTY_EPS = new BigDecimal("0.00005");

    /** Matches {@code DECIMAL(14,4)} on {@code items} and inventory quantity columns. */
    private static final BigDecimal MAX_QTY_14_4 = new BigDecimal("9999999999.9999");

    /** Matches {@code DECIMAL(14,2)} on {@code items.bundle_price} and common money columns. */
    private static final BigDecimal MAX_MONEY_14_2 = new BigDecimal("999999999999.99");

    private static final int DB_QTY_SCALE = 4;

    private static final Map<String, String> ITEM_TYPE_ALIASES = Map.ofEntries(
            Map.entry("retail", "goods"),
            Map.entry("cereals", "goods"),
            Map.entry("cereal", "goods"),
            Map.entry("grocery", "goods"),
            Map.entry("groceries", "goods"),
            Map.entry("spices", "goods"),
            Map.entry("spice", "goods"),
            Map.entry("food", "goods"),
            Map.entry("beverage", "goods"),
            Map.entry("beverages", "goods"),
            Map.entry("drink", "goods"),
            Map.entry("drinks", "goods"),
            Map.entry("product", "goods"),
            Map.entry("products", "goods"),
            Map.entry("merchandise", "goods"));

    private final ObjectMapper objectMapper;
    private final CatalogBootstrapService catalogBootstrapService;
    private final ItemCatalogService itemCatalogService;
    private final ItemRepository itemRepository;
    private final ItemTypeRepository itemTypeRepository;
    private final CategoryRepository categoryRepository;
    private final AisleRepository aisleRepository;
    private final CatalogTaxonomyService catalogTaxonomyService;
    private final PricingService pricingService;
    private final InventoryLedgerService inventoryLedgerService;
    private final BranchRepository branchRepository;

    public CsvImportResponse dryRun(String businessId, byte[] jsonBytes, String branchIdOrNull) {
        catalogBootstrapService.seedDefaultItemTypesIfMissing(businessId);
        ParseResult parsed = parseJson(jsonBytes);
        if (!parsed.globalErrors().isEmpty()) {
            return new CsvImportResponse(true, 0, parsed.globalErrors(), null);
        }
        PreparedImport prepared = prepareLegacyImport(businessId, parsed.rows());
        List<CsvImportLineError> errors =
                validateRows(businessId, prepared.normalized(), branchIdOrNull, prepared.categoryRemap());
        return new CsvImportResponse(true, parsed.rows().size(), errors, null);
    }

    @Transactional
    public CsvImportResponse commit(
            String businessId,
            byte[] jsonBytes,
            String actorUserId,
            String branchIdOrNull
    ) {
        catalogBootstrapService.seedDefaultItemTypesIfMissing(businessId);
        ParseResult parsed = parseJson(jsonBytes);
        if (!parsed.globalErrors().isEmpty()) {
            return new CsvImportResponse(false, 0, parsed.globalErrors(), null);
        }
        PreparedImport prepared = prepareLegacyImport(businessId, parsed.rows());
        List<CsvImportLineError> errors =
                validateRows(businessId, prepared.normalized(), branchIdOrNull, prepared.categoryRemap());
        if (!errors.isEmpty()) {
            return new CsvImportResponse(false, parsed.rows().size(), errors, null);
        }

        LocalDate priceEffective = LocalDate.now(ZoneOffset.UTC);
        Map<String, String> legacyIdToNewItemId = new HashMap<>();
        Map<String, String> aisleCodeToId = new HashMap<>();
        Map<String, String> categoryRemap = prepared.categoryRemap();
        int committed = 0;

        List<NormalizedRow> roots = new ArrayList<>();
        List<NormalizedRow> variants = new ArrayList<>();
        for (NormalizedRow n : prepared.normalized()) {
            LegacyRow r = n.row();
            if (r.parentItemId() == null || r.parentItemId().isBlank()) {
                roots.add(n);
            } else {
                variants.add(n);
            }
        }

        for (NormalizedRow n : roots) {
            LegacyRow r = n.row();
            String itemTypeId = resolveItemTypeId(businessId, r.itemTypeRaw());
            String aisleId = resolveOrCreateAisle(businessId, r, aisleCodeToId);
            CreateItemRequest req = toCreateItemRequest(n, itemTypeId, aisleId, categoryRemap);
            ItemResponse created = itemCatalogService.createItem(businessId, req, null).body();
            legacyIdToNewItemId.put(r.legacyId(), created.id());
            persistLegacyImportSourceId(businessId, created.id(), r.legacyId());
            finishRow(businessId, r, created.id(), priceEffective, branchIdOrNull, actorUserId);
            committed++;
        }

        ArrayDeque<NormalizedRow> pending = new ArrayDeque<>(variants);
        int guard = 0;
        while (!pending.isEmpty() && guard < parsed.rows().size() + 5) {
            guard++;
            int sizeBefore = pending.size();
            Iterator<NormalizedRow> it = pending.iterator();
            while (it.hasNext()) {
                NormalizedRow n = it.next();
                LegacyRow r = n.row();
                String resolvedParent = resolveParentId(businessId, r.parentItemId(), legacyIdToNewItemId);
                if (resolvedParent == null) {
                    continue;
                }
                String variantName = r.variantName() != null && !r.variantName().isBlank()
                        ? r.variantName().trim()
                        : "Variant";
                CreateVariantRequest vr = new CreateVariantRequest(
                        nullIfBlank(n.sku()),
                        variantName,
                        nullIfBlank(n.barcode()),
                        null,
                        null,
                nullIfBlank(effectiveCategoryId(r, categoryRemap)),
                resolveOrCreateAisle(businessId, r, aisleCodeToId),
                safeDbUnitType(r.unitType()),
                        null,
                        null,
                        null,
                        sanitizeQty14_4(r.minStockLevel()),
                        null,
                        null,
                        imageKeyOrNull(r.imageUrl()),
                        null,
                        null
                );
                ItemResponse created = itemCatalogService.createVariant(businessId, resolvedParent, vr);
                legacyIdToNewItemId.put(r.legacyId(), created.id());
                persistLegacyImportSourceId(businessId, created.id(), r.legacyId());
                patchPostVariant(businessId, r, created.id());
                finishRow(businessId, r, created.id(), priceEffective, branchIdOrNull, actorUserId);
                it.remove();
                committed++;
            }
            if (pending.size() == sizeBefore) {
                List<CsvImportLineError> cycleErrors = new ArrayList<>();
                for (NormalizedRow n : pending) {
                    LegacyRow r = n.row();
                    cycleErrors.add(new CsvImportLineError(
                            r.line(),
                            "parent_item_id not found (import order or missing parent): " + r.parentItemId()));
                }
                return new CsvImportResponse(false, parsed.rows().size(), cycleErrors, null);
            }
        }

        return new CsvImportResponse(false, parsed.rows().size(), List.of(), committed);
    }

    /**
     * Stores the legacy export product id on the created {@link Item} so buying-price JSON can resolve
     * {@code product_id} / {@code item_id} to the new Palmart row.
     */
    private void persistLegacyImportSourceId(String businessId, String itemId, String legacyIdRaw) {
        String lid = canonicalExportUuid(legacyIdRaw);
        if (lid == null) {
            return;
        }
        itemRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .ifPresent(item -> {
                    item.setLegacyImportSourceId(lid);
                    itemRepository.save(item);
                });
    }

    private PreparedImport prepareLegacyImport(String businessId, List<LegacyRow> rows) {
        Map<String, String> categoryRemap = resolveLegacyCategoryIds(businessId, rows);
        ensureItemTypesForLegacyImport(businessId, rows);
        List<NormalizedRow> normalized = normalizeSkuBarcode(rows);
        return new PreparedImport(normalized, categoryRemap);
    }

    private Map<String, String> resolveLegacyCategoryIds(String businessId, List<LegacyRow> rows) {
        Map<String, String> remap = new HashMap<>();
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (LegacyRow r : rows) {
            String cid = nullIfBlank(r.categoryId());
            if (cid != null && isUuid(cid)) {
                distinct.add(cid.trim());
            }
        }
        for (String legacyCid : distinct) {
            if (categoryRepository.findByIdAndBusinessId(legacyCid, businessId).isPresent()) {
                remap.put(legacyCid, legacyCid);
                continue;
            }
            Optional<zelisline.ub.catalog.domain.Category> global = categoryRepository.findById(legacyCid);
            String label = firstCategoryNameFor(rows, legacyCid);
            if (global.isEmpty()) {
                catalogTaxonomyService.ensureImportedPlaceholderCategory(businessId, legacyCid, label);
                remap.put(legacyCid, legacyCid);
            } else {
                CategoryResponse created = catalogTaxonomyService.createCategory(
                        businessId,
                        new CreateCategoryRequest(
                                (label != null && !label.isBlank()) ? label : "Imported category",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));
                remap.put(legacyCid, created.id());
            }
        }
        return remap;
    }

    private void ensureItemTypesForLegacyImport(String businessId, List<LegacyRow> rows) {
        Set<String> seen = new HashSet<>();
        for (LegacyRow r : rows) {
            String raw = r.itemTypeRaw() == null ? "" : r.itemTypeRaw().trim();
            String sig = raw.isEmpty() ? "\0empty" : raw;
            if (seen.add(sig)) {
                resolveItemTypeId(businessId, r.itemTypeRaw());
            }
        }
    }

    private List<NormalizedRow> normalizeSkuBarcode(List<LegacyRow> rows) {
        Map<String, Integer> firstLineByBarcode = new HashMap<>();
        for (LegacyRow r : rows) {
            String bc = nullIfBlank(r.barcode());
            if (bc != null) {
                String k = bc.toLowerCase(Locale.ROOT);
                firstLineByBarcode.putIfAbsent(k, r.line());
            }
        }
        List<NormalizedRow> out = new ArrayList<>(rows.size());
        for (LegacyRow r : rows) {
            String bc = nullIfBlank(r.barcode());
            if (bc != null) {
                Integer firstLine = firstLineByBarcode.get(bc.toLowerCase(Locale.ROOT));
                if (firstLine == null || firstLine.intValue() != r.line()) {
                    bc = null;
                }
            }
            String sku = nullIfBlank(r.sku());
            if (sku == null) {
                if (bc != null) {
                    sku = "BC-" + bc;
                } else {
                    sku = "IMP-" + r.legacyId().toLowerCase(Locale.ROOT);
                }
            }
            out.add(new NormalizedRow(r, sku.trim(), bc));
        }
        return out;
    }

    private static String firstCategoryNameFor(List<LegacyRow> rows, String categoryId) {
        for (LegacyRow r : rows) {
            String cid = nullIfBlank(r.categoryId());
            if (cid != null && cid.trim().equals(categoryId)) {
                return nullIfBlank(r.categoryName());
            }
        }
        return null;
    }

    private static String effectiveCategoryId(LegacyRow r, Map<String, String> categoryRemap) {
        String cid = nullIfBlank(r.categoryId());
        if (cid == null) {
            return null;
        }
        String key = cid.trim();
        return categoryRemap.getOrDefault(key, key);
    }

    private record PreparedImport(List<NormalizedRow> normalized, Map<String, String> categoryRemap) {}

    private record NormalizedRow(LegacyRow row, String sku, String barcode) {}

    private void patchPostVariant(String businessId, LegacyRow r, String itemId) {
        boolean patchPack = r.packagingUnitName() != null || r.packagingUnitQty() != null;
        boolean patchBundle =
                r.bundleQty() != null || r.bundlePrice() != null || r.bundleName() != null;
        boolean patchExpiry = r.expiresAfterDays() != null || r.hasExpiryOverride() != null;
        if (!patchPack && !patchBundle && !patchExpiry && r.active() == null) {
            return;
        }
        itemCatalogService.patchItem(
                businessId,
                itemId,
                new PatchItemRequest(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        safePackagingUnitName(r.packagingUnitName()),
                        sanitizeQty14_4(r.packagingUnitQty()),
                        sanitizeBundleQty(r.bundleQty()),
                        sanitizeMoney14_2(r.bundlePrice()),
                        null,
                        safeBundleName(r.bundleName()),
                        null,
                        null,
                        null,
                        sanitizeExpiresDays(r.expiresAfterDays()),
                        r.hasExpiryOverride(),
                        imageKeyOrNull(r.imageUrl()),
                        r.active(),
                        null,
                        null,
                        null
                )
        );
    }

    private void finishRow(
            String businessId,
            LegacyRow r,
            String newItemId,
            LocalDate priceEffective,
            String branchIdOrNull,
            String actorUserId
    ) {
        if (r.active() != null && !r.active()) {
            itemCatalogService.patchItem(
                    businessId,
                    newItemId,
                    new PatchItemRequest(
                            null, null, null, null, null, null, null, null, null, null,
                            null, null, null, null, null, null, null, null, null, null,
                            null, null, null, false, null, null, null));
        }
        BigDecimal sell = sanitizeMoney14_2(r.sellPrice());
        if (sell != null && sell.compareTo(new BigDecimal("0.01")) >= 0) {
            pricingService.setSellingPrice(
                    businessId,
                    new PostSellingPriceRequest(newItemId, null, sell, priceEffective, "legacy json import"),
                    actorUserId
            );
        }
        BigDecimal stock = sanitizeQty14_4(r.stockQty());
        if (branchIdOrNull != null
                && !branchIdOrNull.isBlank()
                && stock != null
                && stock.compareTo(QTY_EPS) > 0) {
            BigDecimal unitCost = openingUnitCost(sell);
            inventoryLedgerService.recordOpeningBalance(
                    businessId,
                    new PostOpeningBalanceRequest(
                            branchIdOrNull.trim(),
                            newItemId,
                            stock,
                            unitCost,
                            "legacy json import opening stock"),
                    actorUserId
            );
        }
    }

    private static BigDecimal openingUnitCost(BigDecimal sellPrice) {
        if (sellPrice != null && sellPrice.compareTo(new BigDecimal("0.01")) >= 0) {
            BigDecimal v = sellPrice.multiply(new BigDecimal("0.01")).setScale(4, RoundingMode.HALF_UP);
            if (v.compareTo(new BigDecimal("0.01")) < 0) {
                return new BigDecimal("0.01");
            }
            return v;
        }
        return new BigDecimal("0.01");
    }

    private String resolveParentId(
            String businessId,
            String parentLegacyId,
            Map<String, String> legacyIdToNewItemId
    ) {
        if (legacyIdToNewItemId.containsKey(parentLegacyId)) {
            return legacyIdToNewItemId.get(parentLegacyId);
        }
        return itemRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(parentLegacyId, businessId)
                .map(row -> row.getId())
                .orElse(null);
    }

    private String resolveOrCreateAisle(String businessId, LegacyRow r, Map<String, String> aisleCodeToId) {
        String code = aisleCode(r);
        if (code == null || code.isBlank()) {
            return null;
        }
        if (aisleCodeToId.containsKey(code)) {
            return aisleCodeToId.get(code);
        }
        Optional<Aisle> existing = aisleRepository.findByBusinessIdAndCode(businessId, code);
        if (existing.isPresent()) {
            aisleCodeToId.put(code, existing.get().getId());
            return existing.get().getId();
        }
        String name = r.aisle() != null && !r.aisle().isBlank()
                ? r.aisle().trim()
                : ("Aisle " + code);
        var created = catalogTaxonomyService.createAisle(
                businessId,
                new CreateAisleRequest(name, code, 0));
        aisleCodeToId.put(code, created.id());
        return created.id();
    }

    private CreateItemRequest toCreateItemRequest(
            NormalizedRow n,
            String itemTypeId,
            String aisleId,
            Map<String, String> categoryRemap
    ) {
        LegacyRow r = n.row();
        return new CreateItemRequest(
                nullIfBlank(n.sku()),
                nullIfBlank(n.barcode()),
                r.name().trim(),
                null,
                itemTypeId,
                nullIfBlank(effectiveCategoryId(r, categoryRemap)),
                aisleId,
                safeDbUnitType(r.unitType()),
                null,
                null,
                null,
                safePackagingUnitName(r.packagingUnitName()),
                sanitizeQty14_4(r.packagingUnitQty()),
                sanitizeBundleQty(r.bundleQty()),
                sanitizeMoney14_2(r.bundlePrice()),
                null,
                safeBundleName(r.bundleName()),
                sanitizeQty14_4(r.minStockLevel()),
                null,
                null,
                sanitizeExpiresDays(r.expiresAfterDays()),
                r.hasExpiryOverride(),
                imageKeyOrNull(r.imageUrl()), null, null);
    }

    private String resolveItemTypeId(String businessId, String itemTypeRaw) {
        String raw = itemTypeRaw == null ? "" : itemTypeRaw.trim();
        if (isUuid(raw)) {
            Optional<ItemType> byId = itemTypeRepository.findByIdAndBusinessId(raw, businessId);
            if (byId.isPresent()) {
                return byId.get().getId();
            }
            return itemTypeRepository
                    .findByBusinessIdAndTypeKey(businessId, "goods")
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "Default item type 'goods' is missing; run catalog bootstrap"))
                    .getId();
        }
        String key = raw.isEmpty() ? "goods" : raw.toLowerCase(Locale.ROOT).replace(' ', '_');
        String mappedKey = ITEM_TYPE_ALIASES.getOrDefault(key, key);
        Optional<ItemType> byMapped = itemTypeRepository.findByBusinessIdAndTypeKey(businessId, mappedKey);
        if (byMapped.isPresent()) {
            return byMapped.get().getId();
        }
        if (!mappedKey.equals(key)) {
            Optional<ItemType> byKey = itemTypeRepository.findByBusinessIdAndTypeKey(businessId, key);
            if (byKey.isPresent()) {
                return byKey.get().getId();
            }
        }
        return createAdhocItemType(businessId, key).getId();
    }

    private ItemType createAdhocItemType(String businessId, String typeKey) {
        List<ItemType> all = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(businessId);
        int max = all.stream().mapToInt(ItemType::getSortOrder).max().orElse(-1);
        ItemType row = new ItemType();
        row.setBusinessId(businessId);
        row.setTypeKey(typeKey);
        row.setLabel(humanizeItemTypeKey(typeKey));
        row.setSortOrder(max + 1);
        row.setActive(true);
        return itemTypeRepository.save(row);
    }

    private static String humanizeItemTypeKey(String key) {
        if (key == null || key.isBlank()) {
            return "Item type";
        }
        String[] parts = key.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) {
                sb.append(p.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.length() == 0 ? key : sb.toString();
    }

    private List<CsvImportLineError> validateRows(
            String businessId,
            List<NormalizedRow> normalized,
            String branchIdOrNull,
            Map<String, String> categoryRemap
    ) {
        List<CsvImportLineError> errors = new ArrayList<>();
        Set<String> seenSku = new HashSet<>();
        Set<String> seenBarcode = new HashSet<>();
        Set<String> idsInFile = new HashSet<>();
        for (NormalizedRow n : normalized) {
            idsInFile.add(n.row().legacyId());
        }

        if (branchIdOrNull != null && !branchIdOrNull.isBlank()) {
            Optional<Branch> br =
                    branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchIdOrNull, businessId);
            if (br.isEmpty()) {
                errors.add(new CsvImportLineError(0, "branch not found for this business"));
            }
        }

        for (NormalizedRow n : normalized) {
            LegacyRow r = n.row();
            int line = r.line();
            if (!isUuid(r.legacyId())) {
                errors.add(new CsvImportLineError(line, "id must be a UUID"));
                continue;
            }
            if (r.name() == null || r.name().isBlank()) {
                errors.add(new CsvImportLineError(line, "name is required"));
            }
            String sku = n.sku();
            if (sku == null || sku.isBlank()) {
                errors.add(new CsvImportLineError(line, "resolved sku is empty (internal import error)"));
            } else {
                String skuKey = sku.trim().toLowerCase(Locale.ROOT);
                if (!seenSku.add(skuKey)) {
                    errors.add(new CsvImportLineError(line, "duplicate sku in file: " + sku));
                }
                if (itemRepository.existsByBusinessIdAndSkuAndDeletedAtIsNull(businessId, sku.trim())) {
                    errors.add(new CsvImportLineError(line, "sku already exists: " + sku));
                }
            }
            String barcode = n.barcode();
            if (barcode != null && !barcode.isBlank()) {
                String bcKey = barcode.trim().toLowerCase(Locale.ROOT);
                if (!seenBarcode.add(bcKey)) {
                    errors.add(new CsvImportLineError(line, "duplicate barcode in file: " + barcode));
                }
                itemRepository.findByBusinessIdAndBarcodeAndDeletedAtIsNull(businessId, barcode.trim()).ifPresent(clash ->
                        errors.add(new CsvImportLineError(
                                line,
                                "barcode already used by sku " + clash.getSku())));
            }

            String pit = r.parentItemId();
            if (pit != null && !pit.isBlank()) {
                if (!idsInFile.contains(pit)
                        && itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(pit, businessId).isEmpty()) {
                    errors.add(new CsvImportLineError(
                            line,
                            "parent_item_id not found in file or catalog: " + pit));
                }
            }

            String cid = nullIfBlank(r.categoryId());
            if (cid != null) {
                if (!isUuid(cid)) {
                    errors.add(new CsvImportLineError(line, "category_id must be a UUID when set"));
                } else {
                    String trimmed = cid.trim();
                    if (!categoryRemap.containsKey(trimmed)) {
                        errors.add(new CsvImportLineError(line, "category_id could not be resolved: " + cid));
                    } else if (categoryRepository
                            .findByIdAndBusinessId(categoryRemap.get(trimmed), businessId)
                            .isEmpty()) {
                        errors.add(new CsvImportLineError(line, "category not found after import prep: " + cid));
                    }
                }
            }

            resolveItemTypeId(businessId, r.itemTypeRaw());

            if (r.sellPrice() != null && r.sellPrice().compareTo(BigDecimal.ZERO) > 0
                    && r.sellPrice().compareTo(new BigDecimal("0.01")) < 0) {
                errors.add(new CsvImportLineError(line, "current_sell_price must be >= 0.01 when set"));
            }

            if (r.stockQty() != null && r.stockQty().compareTo(QTY_EPS) > 0) {
                if (branchIdOrNull == null || branchIdOrNull.isBlank()) {
                    errors.add(new CsvImportLineError(
                            line,
                            "branchId is required when current_stock > 0 (opening balance)"));
                }
            }
        }
        return errors;
    }

    private ParseResult parseJson(byte[] jsonBytes) {
        List<CsvImportLineError> globalErrors = new ArrayList<>();
        JsonNode root;
        try {
            root = objectMapper.readTree(jsonBytes);
        } catch (IOException e) {
            globalErrors.add(new CsvImportLineError(0, "Invalid JSON: " + e.getMessage()));
            return new ParseResult(List.of(), globalErrors);
        }
        JsonNode array = null;
        if (root != null && root.isArray()) {
            array = root;
        } else if (root != null && root.isObject()) {
            if (root.has("products") && root.get("products").isArray()) {
                array = root.get("products");
            } else if (root.has("items") && root.get("items").isArray()) {
                array = root.get("items");
            } else if (root.has("data") && root.get("data").isArray()) {
                array = root.get("data");
            } else if (root.has("results") && root.get("results").isArray()) {
                array = root.get("results");
            } else if (root.has("records") && root.get("records").isArray()) {
                array = root.get("records");
            }
        }
        if (array == null || !array.isArray()) {
            globalErrors.add(new CsvImportLineError(
                    0,
                    "Expected a JSON array or an object with a \"products\" / \"items\" / \"data\" / \"results\" / \"records\" array"));
            return new ParseResult(List.of(), globalErrors);
        }
        List<LegacyRow> rows = new ArrayList<>();
        int i = 0;
        for (JsonNode n : array) {
            i++;
            if (n == null || !n.isObject()) {
                globalErrors.add(new CsvImportLineError(i, "Each entry must be a JSON object"));
                continue;
            }
            rows.add(LegacyRow.fromJson(i, n));
        }
        if (!globalErrors.isEmpty()) {
            return new ParseResult(List.of(), globalErrors);
        }
        return new ParseResult(rows, List.of());
    }

    private static String nullIfBlank(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    /** {@code items.unit_type} is {@code VARCHAR(16)} — clip legacy values so inserts do not fail. */
    private static String safeDbUnitType(String raw) {
        String t = nullIfBlank(raw);
        if (t == null) {
            return null;
        }
        return t.length() <= 16 ? t : t.substring(0, 16);
    }

    private static String safePackagingUnitName(String raw) {
        String t = nullIfBlank(raw);
        if (t == null) {
            return null;
        }
        return t.length() <= 255 ? t : t.substring(0, 255);
    }

    private static String safeBundleName(String raw) {
        String t = nullIfBlank(raw);
        if (t == null) {
            return null;
        }
        return t.length() <= 255 ? t : t.substring(0, 255);
    }

    /**
     * Legacy exports sometimes carry sentinel/huge numbers; MySQL {@code DECIMAL(14,4)} rejects them
     * ({@code 1264 Out of range}).
     */
    private static BigDecimal sanitizeQty14_4(BigDecimal v) {
        if (v == null) {
            return null;
        }
        BigDecimal x = v;
        if (x.signum() < 0) {
            x = BigDecimal.ZERO;
        }
        if (x.compareTo(MAX_QTY_14_4) > 0) {
            x = MAX_QTY_14_4;
        }
        return x.setScale(DB_QTY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal sanitizeMoney14_2(BigDecimal v) {
        if (v == null) {
            return null;
        }
        BigDecimal x = v;
        if (x.signum() < 0) {
            x = BigDecimal.ZERO;
        }
        if (x.compareTo(MAX_MONEY_14_2) > 0) {
            x = MAX_MONEY_14_2;
        }
        return x.setScale(2, RoundingMode.HALF_UP);
    }

    private static Integer sanitizeBundleQty(Integer v) {
        if (v == null) {
            return null;
        }
        if (v < 0) {
            return 0;
        }
        if (v > 1_000_000_000) {
            return 1_000_000_000;
        }
        return v;
    }

    private static Integer sanitizeExpiresDays(Integer v) {
        if (v == null) {
            return null;
        }
        if (v < 1) {
            return 1;
        }
        if (v > 365_000) {
            return 365_000;
        }
        return v;
    }

    private static String imageKeyOrNull(String url) {
        String u = nullIfBlank(url);
        if (u == null) {
            return null;
        }
        if (u.startsWith("http://") || u.startsWith("https://")) {
            return u;
        }
        return u;
    }

    private static boolean isUuid(String s) {
        return s != null && UUID_REGEX.matcher(s).matches();
    }

    private static String aisleCode(LegacyRow r) {
        String numPart = r.aisleNumber() == null ? "" : String.valueOf(r.aisleNumber()).trim();
        String namePart = r.aisle() == null ? "" : r.aisle().trim();
        if (numPart.isEmpty() && namePart.isEmpty()) {
            return null;
        }
        if (!numPart.isEmpty()) {
            String slug = slugCode(numPart);
            return slug.isEmpty() ? "aisle-" + Math.abs(numPart.hashCode()) : slug;
        }
        String slug = slugCode(namePart);
        return slug.isEmpty() ? "aisle-import" : slug;
    }

    private static String slugCode(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > 191) {
            return s.substring(0, 191);
        }
        return s;
    }

    private record ParseResult(List<LegacyRow> rows, List<CsvImportLineError> globalErrors) {}

    private record LegacyRow(
            int line,
            String legacyId,
            String businessIdFromFile,
            String categoryId,
            String categoryName,
            String parentItemId,
            String name,
            String variantName,
            String unitType,
            String itemTypeRaw,
            BigDecimal stockQty,
            BigDecimal minStockLevel,
            BigDecimal sellPrice,
            String imageUrl,
            String packagingUnitName,
            BigDecimal packagingUnitQty,
            Boolean active,
            String barcode,
            Integer expiresAfterDays,
            Boolean hasExpiryOverride,
            Integer bundleQty,
            BigDecimal bundlePrice,
            String bundleName,
            String aisle,
            String aisleNumber,
            String sku
    ) {
        static LegacyRow fromJson(int line, JsonNode n) {
            String nm = clipName(legacyProductNameFromExport(n, 0));
            if (nm == null || nm.isBlank()) {
                nm = clipName(textAny(n, "product_code", "productCode", "sku", "item_sku", "itemSku"));
            }
            return new LegacyRow(
                    line,
                    legacyProductIdFromExport(n, 0),
                    text(n, "business_id"),
                    text(n, "category_id"),
                    text(n, "category_name"),
                    normalizeUuidCase(text(n, "parent_item_id")),
                    nm,
                    text(n, "variant_name"),
                    text(n, "unit_type"),
                    text(n, "item_type"),
                    decimal(n, "current_stock"),
                    decimal(n, "min_stock_level"),
                    decimal(n, "current_sell_price"),
                    text(n, "image_url"),
                    text(n, "packaging_unit_name"),
                    decimal(n, "packaging_unit_qty"),
                    triBool(n, "active"),
                    text(n, "barcode"),
                    expiryDays(n, "expiry_date"),
                    expiryDays(n, "expiry_date") != null ? Boolean.TRUE : null,
                    bundleQtyInt(n, "bundle_quantity"),
                    decimal(n, "bundle_price"),
                    text(n, "bundle_name"),
                    text(n, "aisle"),
                    aisleNumberText(n),
                    text(n, "product_code")
            );
        }
    }

    /**
     * Catalog UUID for legacy correlation with {@link LegacyBuyingPriceJsonImportService} (same key precedence as
     * {@code legacyBuyingItemIdFromExport} on the flat object): prefer {@code item_id} / {@code product_id} and other
     * catalog aliases, and use top-level {@code id} last so exports that carry both a row/shell {@code id} and a
     * distinct catalog {@code item_id} store the same value on {@code items.legacy_import_source_id} that buying-price
     * JSON resolves.
     */
    private static String legacyProductIdFromExport(JsonNode n, int depth) {
        if (n == null || depth > 4) {
            return null;
        }
        String flat = textAny(
                n,
                "item_id",
                "itemId",
                "product_id",
                "productId",
                "catalog_product_id",
                "catalogProductId",
                "catalog_item_id",
                "catalogItemId",
                "stock_item_id",
                "stockItemId",
                "inventory_item_id",
                "inventoryItemId",
                "inventory_id",
                "inventoryId",
                "legacy_product_id",
                "legacyProductId",
                "legacy_item_id",
                "legacyItemId",
                "product_uuid",
                "productUuid",
                "uuid",
                "external_id",
                "externalId",
                "id");
        if (flat != null) {
            String c = canonicalExportUuid(flat);
            if (c != null) {
                return c;
            }
        }
        for (String nest : new String[] {"product", "item", "data", "record", "attributes", "fields", "catalog"}) {
            if (n.has(nest) && n.get(nest).isObject()) {
                String inner = legacyProductIdFromExport(n.get(nest), depth + 1);
                if (inner != null) {
                    return inner;
                }
            }
        }
        return null;
    }

    /** Lowercase UUID string for stable {@code legacy_import_source_id} and {@code IMP-} SKUs, or null. */
    private static String canonicalExportUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.trim();
        if (!UUID_REGEX.matcher(t).matches()) {
            return null;
        }
        return t.toLowerCase(Locale.ROOT);
    }

    /** If {@code raw} is a UUID shape, return canonical lowercase; otherwise trimmed non-blank text or null. */
    private static String normalizeUuidCase(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.trim();
        if (UUID_REGEX.matcher(t).matches()) {
            return t.toLowerCase(Locale.ROOT);
        }
        return t;
    }

    private static String legacyProductNameFromExport(JsonNode n, int depth) {
        if (n == null || depth > 4) {
            return null;
        }
        String v = textAny(
                n,
                "name",
                "product_name",
                "productName",
                "title",
                "item_name",
                "itemName",
                "label");
        if (v != null) {
            return v;
        }
        for (String nest : new String[] {"product", "item", "data", "record", "attributes", "fields", "catalog"}) {
            if (n.has(nest) && n.get(nest).isObject()) {
                String inner = legacyProductNameFromExport(n.get(nest), depth + 1);
                if (inner != null) {
                    return inner;
                }
            }
        }
        return null;
    }

    /** Matches {@code items.name} length. */
    private static String clipName(String s) {
        if (s == null) {
            return null;
        }
        if (s.length() <= 500) {
            return s;
        }
        return s.substring(0, 500);
    }

    private static String textAny(JsonNode n, String... fields) {
        for (String f : fields) {
            String v = text(n, f);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String text(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return null;
        }
        JsonNode v = n.get(field);
        if (v.isTextual()) {
            String s = v.asText();
            return s == null || s.isBlank() ? null : s;
        }
        if (v.isNumber()) {
            return v.asText();
        }
        return null;
    }

    private static BigDecimal decimal(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return null;
        }
        JsonNode v = n.get(field);
        try {
            if (v.isNumber()) {
                return BigDecimal.valueOf(v.doubleValue());
            }
            if (v.isTextual()) {
                return new BigDecimal(v.asText().trim());
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    private static Boolean triBool(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return null;
        }
        JsonNode v = n.get(field);
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        if (v.isInt() || v.isLong()) {
            return v.intValue() != 0;
        }
        if (v.isTextual()) {
            String s = v.asText().trim().toLowerCase(Locale.ROOT);
            return switch (s) {
                case "true", "1", "yes", "y" -> Boolean.TRUE;
                case "false", "0", "no", "n" -> Boolean.FALSE;
                default -> null;
            };
        }
        return null;
    }

    private static boolean expiryPresent(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return false;
        }
        JsonNode v = n.get(field);
        if (v.isNumber()) {
            return v.doubleValue() > 0;
        }
        if (v.isTextual()) {
            return !v.asText().isBlank();
        }
        return false;
    }

    private static Integer expiryDays(JsonNode n, String field) {
        if (!expiryPresent(n, field)) {
            return null;
        }
        JsonNode v = n.get(field);
        long epochSec;
        try {
            if (v.isNumber()) {
                double d = v.doubleValue();
                epochSec = d > 1e12 ? (long) (d / 1000.0) : (long) d;
            } else {
                epochSec = Long.parseLong(v.asText().trim());
                if (epochSec > 1_000_000_000_000L) {
                    epochSec = epochSec / 1000;
                }
            }
        } catch (NumberFormatException e) {
            return null;
        }
        Instant exp = Instant.ofEpochSecond(epochSec);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        long days = ChronoUnit.DAYS.between(today, exp.atZone(ZoneOffset.UTC).toLocalDate());
        if (days < 1L) {
            days = 1L;
        }
        return (int) Math.min(days, Integer.MAX_VALUE);
    }

    private static Integer bundleQtyInt(JsonNode n, String field) {
        BigDecimal d = decimal(n, field);
        if (d == null) {
            return null;
        }
        return d.intValue();
    }

    private static String aisleNumberText(JsonNode n) {
        if (n == null || !n.has("aisle_number") || n.get("aisle_number").isNull()) {
            return null;
        }
        JsonNode v = n.get("aisle_number");
        if (v.isTextual() || v.isNumber()) {
            String s = v.asText().trim();
            return s.isEmpty() ? null : s;
        }
        return null;
    }
}
