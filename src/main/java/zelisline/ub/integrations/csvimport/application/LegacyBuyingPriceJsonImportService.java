package zelisline.ub.integrations.csvimport.application;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.integrations.csvimport.api.dto.CsvImportLineError;
import zelisline.ub.integrations.csvimport.api.dto.CsvImportResponse;
import zelisline.ub.pricing.PricingConstants;
import zelisline.ub.pricing.api.dto.PostBuyingPriceRequest;
import zelisline.ub.pricing.application.PricingService;
import zelisline.ub.suppliers.SupplierCodes;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;

/**
 * Imports legacy buying-price rows (unit cost history).
 *
 * <p><b>Expected JSON shape per row</b> (snake_case or camelCase keys):
 * <ul>
 *   <li>{@code item_id} / {@code itemId} / {@code product_id} / {@code productId} — UUID of the catalog item; resolves to an
 *       {@code items} row by primary id, {@link Item#getLegacyImportSourceId()}, synthetic SKU {@code IMP-&lt;uuid&gt;}
 *       (assigned when legacy products had no {@code product_code}), optional {@code product_code} / {@code sku}, or
 *       {@code barcode}. Top-level {@code id}, when {@code item_id} is absent, is used only as a catalog id on shallow
 *       rows; when both are present, {@code id} is typically the buying-price row and is ignored for resolution.</li>
 *   <li>{@code supplier_id} / {@code supplierId} — UUID; or use {@code supplier_code} / {@code vendor_code} alone;
 *       resolves by id, {@link Supplier#getLegacyImportSourceId()}, or supplier {@code code}. Unknown UUIDs fall back to
 *       SYS-UNASSIGNED when present and a note prefix records the original id. If omitted entirely, uses
 *       SYS-UNASSIGNED.</li>
 *   <li>{@code price} — float; stored as {@code unit_cost} (also accepts {@code unit_cost} / {@code unitCost}).</li>
 *   <li>{@code effective_from} / {@code effectiveFrom} — unix time (seconds or milliseconds) → effective calendar date (UTC).</li>
 *   <li>{@code notes} — optional string (trimmed, max 2000 chars).</li>
 * </ul>
 *
 * <p><b>Omitted on write</b> (export-only fields; not applied to new rows):
 * {@code id}, {@code set_by} / {@code setBy} (setter is the signed-in importer),
 * {@code created_at} / {@code createdAt} (DB {@code created_at} is the import time).
 *
 * <p><b>Wrappers</b>: top-level array, or an object with {@code buying_prices}, {@code buyingPrices}, or {@code costs}.
 */
@Service
@RequiredArgsConstructor
public class LegacyBuyingPriceJsonImportService {

    private static final String[] LEGACY_JSON_NEST_KEYS = {
        "product", "item", "data", "record", "attributes", "fields", "catalog"
    };

    private static final Pattern UUID_REGEX = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final BigDecimal MIN_UNIT_COST = new BigDecimal("0.0001");
    private static final BigDecimal MAX_UNIT_COST_14_4 = new BigDecimal("9999999999.9999");

    private final ObjectMapper objectMapper;
    private final ItemRepository itemRepository;
    private final SupplierRepository supplierRepository;
    private final PricingService pricingService;

    public CsvImportResponse dryRun(String businessId, byte[] jsonBytes) {
        ParseResult parsed = parseJson(jsonBytes);
        if (!parsed.globalErrors().isEmpty()) {
            return new CsvImportResponse(true, 0, parsed.globalErrors(), null);
        }
        ValidationOutcome vo = validateBuyingRows(businessId, parsed.rows());
        return new CsvImportResponse(true, parsed.rows().size(), vo.errors(), null);
    }

    @Transactional
    public CsvImportResponse commit(String businessId, byte[] jsonBytes, String actorUserId) {
        ParseResult parsed = parseJson(jsonBytes);
        if (!parsed.globalErrors().isEmpty()) {
            return new CsvImportResponse(false, 0, parsed.globalErrors(), null);
        }
        ValidationOutcome vo = validateBuyingRows(businessId, parsed.rows());
        if (!vo.errors().isEmpty()) {
            return new CsvImportResponse(false, parsed.rows().size(), vo.errors(), null);
        }
        int n = 0;
        for (ResolvedBuyingRow r : vo.resolved()) {
            pricingService.setBuyingPrice(
                    businessId,
                    new PostBuyingPriceRequest(
                            r.itemId(),
                            r.supplierId(),
                            r.unitCost(),
                            r.effectiveFrom(),
                            PricingConstants.BUYING_SOURCE_LEGACY_JSON,
                            r.notes()),
                    actorUserId);
            n++;
        }
        return new CsvImportResponse(false, parsed.rows().size(), List.of(), n);
    }

    private ValidationOutcome validateBuyingRows(String businessId, List<BuyingRow> rows) {
        List<CsvImportLineError> errors = new ArrayList<>();
        List<ResolvedBuyingRow> resolved = new ArrayList<>();
        for (BuyingRow r : rows) {
            int line = r.line();
            String itemRaw = r.itemId() == null ? "" : r.itemId().trim();
            String skuFb = r.skuOrProductCode() == null ? "" : r.skuOrProductCode().trim();
            String barcodeFb = r.barcodeRaw() == null ? "" : r.barcodeRaw().trim();
            String notePrefix = null;
            Optional<String> itemId = Optional.empty();
            if (itemRaw.isEmpty() && skuFb.isEmpty() && barcodeFb.isEmpty()) {
                errors.add(new CsvImportLineError(
                        line, "product_id, item_id, product_code/sku, or barcode is required"));
            } else {
                itemId = resolveCatalogItemId(businessId, itemRaw, skuFb, barcodeFb);
                if (itemId.isEmpty()) {
                    List<String> hint = new ArrayList<>();
                    if (!itemRaw.isEmpty()) {
                        hint.add("id=" + itemRaw);
                    }
                    if (!skuFb.isEmpty()) {
                        hint.add("sku=" + skuFb);
                    }
                    if (!barcodeFb.isEmpty()) {
                        hint.add("barcode=" + barcodeFb);
                    }
                    errors.add(new CsvImportLineError(
                            line, "product/item not found (" + String.join(", ", hint) + ")"));
                }
            }

            Optional<String> supplierId = Optional.empty();
            String supRaw = r.supplierIdRaw() == null ? "" : r.supplierIdRaw().trim();
            String supCode = r.supplierCodeRaw() == null ? "" : r.supplierCodeRaw().trim();
            if (supRaw.isEmpty() && supCode.isEmpty()) {
                supplierId = supplierRepository
                        .findByBusinessIdAndCodeAndDeletedAtIsNull(businessId, SupplierCodes.SYSTEM_UNASSIGNED)
                        .map(Supplier::getId);
                if (supplierId.isEmpty()) {
                    errors.add(new CsvImportLineError(
                            line,
                            "System supplier with code " + SupplierCodes.SYSTEM_UNASSIGNED
                                    + " not found; required when supplier_id is omitted"));
                }
            } else if (!supRaw.isEmpty()) {
                if (isUuid(supRaw)) {
                    supplierId = resolveBuyingSupplierId(businessId, supRaw, supCode);
                    if (supplierId.isEmpty()) {
                        supplierId = supplierRepository
                                .findByBusinessIdAndCodeAndDeletedAtIsNull(
                                        businessId, SupplierCodes.SYSTEM_UNASSIGNED)
                                .map(Supplier::getId);
                        if (supplierId.isPresent()) {
                            notePrefix = "[import: unresolved supplier_id " + supRaw + "] ";
                        } else {
                            errors.add(new CsvImportLineError(line, "supplier not found: " + supRaw));
                        }
                    }
                } else {
                    supplierId = supplierRepository
                            .findByBusinessIdAndCodeAndDeletedAtIsNull(businessId, supRaw)
                            .map(Supplier::getId);
                    if (supplierId.isEmpty()) {
                        errors.add(new CsvImportLineError(line, "supplier not found (code): " + supRaw));
                    }
                }
            } else {
                supplierId = resolveBuyingSupplierId(businessId, null, supCode);
                if (supplierId.isEmpty()) {
                    errors.add(new CsvImportLineError(line, "supplier not found for code: " + supCode));
                }
            }

            if (r.effectiveFrom() == null) {
                errors.add(new CsvImportLineError(line, "effective_from is required (unix time)"));
            }
            if (r.unitCost() == null || r.unitCost().compareTo(MIN_UNIT_COST) < 0) {
                errors.add(new CsvImportLineError(line, "price / unit_cost must be >= 0.0001 when set"));
            }

            if (itemId.isPresent()
                    && supplierId.isPresent()
                    && r.effectiveFrom() != null
                    && r.unitCost() != null
                    && r.unitCost().compareTo(MIN_UNIT_COST) >= 0) {
                resolved.add(new ResolvedBuyingRow(
                        line,
                        itemId.get(),
                        supplierId.get(),
                        r.unitCost(),
                        r.effectiveFrom(),
                        mergeImportNotePrefix(notePrefix, r.notes())));
            }
        }
        return new ValidationOutcome(errors, resolved);
    }

    private Optional<String> resolveCatalogItemId(
            String businessId,
            String uuidRaw,
            String skuFromFile,
            String barcodeFromFile
    ) {
        String uRaw = uuidRaw == null ? "" : uuidRaw.trim();
        if (!uRaw.isEmpty() && isUuid(uRaw)) {
            String uCanon = uRaw.toLowerCase(Locale.ROOT);
            Optional<Item> byPk = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(uRaw, businessId);
            if (byPk.isEmpty() && !uCanon.equals(uRaw)) {
                byPk = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(uCanon, businessId);
            }
            if (byPk.isPresent()) {
                return Optional.of(byPk.get().getId());
            }
            Optional<Item> byLegacy =
                    itemRepository.findByBusinessIdAndLegacyImportSourceIdAndDeletedAtIsNull(businessId, uCanon);
            if (byLegacy.isEmpty() && !uCanon.equals(uRaw)) {
                byLegacy = itemRepository.findByBusinessIdAndLegacyImportSourceIdAndDeletedAtIsNull(businessId, uRaw);
            }
            if (byLegacy.isPresent()) {
                return Optional.of(byLegacy.get().getId());
            }
            Optional<Item> byImpSku =
                    itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(businessId, "IMP-" + uCanon);
            if (byImpSku.isEmpty()) {
                byImpSku = itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(businessId, "IMP-" + uRaw);
            }
            if (byImpSku.isPresent()) {
                return Optional.of(byImpSku.get().getId());
            }
            // Legacy catalogs sometimes store the export UUID as product_code / sku (not IMP-prefixed).
            Optional<Item> bySkuEqualsUuid =
                    itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(businessId, uCanon);
            if (bySkuEqualsUuid.isEmpty() && !uCanon.equals(uRaw)) {
                bySkuEqualsUuid =
                        itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(businessId, uRaw);
            }
            if (bySkuEqualsUuid.isPresent()) {
                return Optional.of(bySkuEqualsUuid.get().getId());
            }
            Optional<Item> byBarcodeEqualsUuid =
                    itemRepository.findByBusinessIdAndBarcodeAndDeletedAtIsNull(businessId, uCanon);
            if (byBarcodeEqualsUuid.isEmpty() && !uCanon.equals(uRaw)) {
                byBarcodeEqualsUuid =
                        itemRepository.findByBusinessIdAndBarcodeAndDeletedAtIsNull(businessId, uRaw);
            }
            if (byBarcodeEqualsUuid.isPresent()) {
                return Optional.of(byBarcodeEqualsUuid.get().getId());
            }
        }
        String skuFb = skuFromFile == null ? "" : skuFromFile.trim();
        if (!skuFb.isEmpty()) {
            Optional<Item> row = itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(businessId, skuFb);
            if (row.isPresent()) {
                return Optional.of(row.get().getId());
            }
        }
        String bc = barcodeFromFile == null ? "" : barcodeFromFile.trim();
        if (!bc.isEmpty()) {
            Optional<Item> byBarcode =
                    itemRepository.findByBusinessIdAndBarcodeAndDeletedAtIsNull(businessId, bc);
            if (byBarcode.isPresent()) {
                return Optional.of(byBarcode.get().getId());
            }
            Optional<Item> byBcSku =
                    itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(businessId, "BC-" + bc);
            if (byBcSku.isPresent()) {
                return Optional.of(byBcSku.get().getId());
            }
        }
        return Optional.empty();
    }

    private Optional<String> resolveBuyingSupplierId(String businessId, String uuidRaw, String codeFallback) {
        if (uuidRaw != null && !uuidRaw.isBlank()) {
            String s = uuidRaw.trim();
            if (isUuid(s)) {
                if (supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(s, businessId).isPresent()) {
                    return Optional.of(s);
                }
                Optional<Supplier> leg =
                        supplierRepository.findByBusinessIdAndLegacyImportSourceIdAndDeletedAtIsNull(businessId, s);
                if (leg.isPresent()) {
                    return Optional.of(leg.get().getId());
                }
            }
            Optional<Supplier> byCodeField =
                    supplierRepository.findByBusinessIdAndCodeAndDeletedAtIsNull(businessId, s);
            if (byCodeField.isPresent()) {
                return Optional.of(byCodeField.get().getId());
            }
        }
        if (codeFallback != null && !codeFallback.isBlank()) {
            return supplierRepository
                    .findByBusinessIdAndCodeAndDeletedAtIsNull(businessId, codeFallback.trim())
                    .map(Supplier::getId);
        }
        return Optional.empty();
    }

    private static String mergeImportNotePrefix(String prefix, String userNotes) {
        String p = prefix == null || prefix.isBlank() ? "" : prefix.trim();
        String u = userNotes == null || userNotes.isBlank() ? "" : userNotes.trim();
        if (p.isEmpty()) {
            return u.isEmpty() ? null : clip(u, 2000);
        }
        if (u.isEmpty()) {
            return clip(p, 2000);
        }
        return clip(p + u, 2000);
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
            if (root.has("buying_prices") && root.get("buying_prices").isArray()) {
                array = root.get("buying_prices");
            } else if (root.has("buyingPrices") && root.get("buyingPrices").isArray()) {
                array = root.get("buyingPrices");
            } else if (root.has("costs") && root.get("costs").isArray()) {
                array = root.get("costs");
            }
        }
        if (array == null || !array.isArray()) {
            globalErrors.add(new CsvImportLineError(
                    0,
                    "Expected a JSON array or an object with \"buying_prices\", \"buyingPrices\", or \"costs\""));
            return new ParseResult(List.of(), globalErrors);
        }
        List<BuyingRow> rows = new ArrayList<>();
        int i = 0;
        for (JsonNode n : array) {
            i++;
            if (n == null || !n.isObject()) {
                globalErrors.add(new CsvImportLineError(i, "Each entry must be a JSON object"));
                continue;
            }
            rows.add(BuyingRow.fromJson(i, n));
        }
        if (!globalErrors.isEmpty()) {
            return new ParseResult(List.of(), globalErrors);
        }
        return new ParseResult(rows, List.of());
    }

    private record ValidationOutcome(List<CsvImportLineError> errors, List<ResolvedBuyingRow> resolved) {}

    private record ResolvedBuyingRow(
            int line,
            String itemId,
            String supplierId,
            BigDecimal unitCost,
            LocalDate effectiveFrom,
            String notes
    ) {}

    private record ParseResult(List<BuyingRow> rows, List<CsvImportLineError> globalErrors) {}

    private record BuyingRow(
            int line,
            String itemId,
            String skuOrProductCode,
            String barcodeRaw,
            String supplierIdRaw,
            String supplierCodeRaw,
            BigDecimal unitCost,
            LocalDate effectiveFrom,
            String notes
    ) {
        static BuyingRow fromJson(int line, JsonNode n) {
            return new BuyingRow(
                    line,
                    legacyBuyingItemIdFromExport(n, 0),
                    textAnyNested(
                            n,
                            0,
                            "product_code",
                            "productCode",
                            "sku",
                            "item_sku",
                            "itemSku",
                            "vendor_sku",
                            "vendorSku",
                            "supplier_sku",
                            "supplierSku"),
                    textAnyNested(
                            n,
                            0,
                            "barcode",
                            "product_barcode",
                            "productBarcode",
                            "ean",
                            "upc"),
                    textAny(n, "supplier_id", "supplierId"),
                    textAny(n, "supplier_code", "supplierCode", "vendor_code", "vendorCode"),
                    unitCostField(n),
                    effectiveFromUnix(n),
                    nullIfBlank(clip(text(n, "notes"), 2000)));
        }
    }

    /**
     * Legacy catalog UUID for the line (same nesting rules as {@link LegacyProductJsonImportService}).
     * Returns lowercase canonical form when a UUID is found.
     *
     * <p><b>Field precedence</b>: Many buying-price exports set top-level {@code id} to the <em>price row</em>
     * UUID and put the sellable SKU / catalog row in {@code item_id} or {@code product_id}. Those keys are
     * therefore tried before {@code id}; nested {@code product}/{@code item} objects still use {@code id}
     * when no {@code item_id} is present there.
     */
    private static String legacyBuyingItemIdFromExport(JsonNode n, int depth) {
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
            String t = flat.trim();
            if (UUID_REGEX.matcher(t).matches()) {
                return t.toLowerCase(Locale.ROOT);
            }
        }
        for (String nest : LEGACY_JSON_NEST_KEYS) {
            if (n.has(nest) && n.get(nest).isObject()) {
                String inner = legacyBuyingItemIdFromExport(n.get(nest), depth + 1);
                if (inner != null) {
                    return inner;
                }
            }
        }
        return null;
    }

    private static String textAnyNested(JsonNode n, int depth, String... fields) {
        if (n == null || depth > 4) {
            return null;
        }
        String v = textAny(n, fields);
        if (v != null) {
            return v;
        }
        for (String nest : LEGACY_JSON_NEST_KEYS) {
            if (n.has(nest) && n.get(nest).isObject()) {
                String inner = textAnyNested(n.get(nest), depth + 1, fields);
                if (inner != null) {
                    return inner;
                }
            }
        }
        return null;
    }

    private static BigDecimal unitCostField(JsonNode n) {
        BigDecimal p = decimalAny(n, "price", "unit_cost", "unitCost");
        return sanitizeUnitCost(p);
    }

    private static BigDecimal sanitizeUnitCost(BigDecimal v) {
        if (v == null) {
            return null;
        }
        BigDecimal x = v;
        if (x.signum() < 0) {
            x = BigDecimal.ZERO;
        }
        if (x.compareTo(MAX_UNIT_COST_14_4) > 0) {
            x = MAX_UNIT_COST_14_4;
        }
        return x.setScale(4, RoundingMode.HALF_UP);
    }

    private static LocalDate effectiveFromUnix(JsonNode n) {
        if (n == null || !n.has("effective_from") || n.get("effective_from").isNull()) {
            if (n != null && n.has("effectiveFrom") && !n.get("effectiveFrom").isNull()) {
                return epochToLocalDate(n.get("effectiveFrom"));
            }
            return null;
        }
        return epochToLocalDate(n.get("effective_from"));
    }

    private static LocalDate epochToLocalDate(JsonNode v) {
        long epochSec;
        try {
            if (v.isNumber()) {
                double d = v.doubleValue();
                epochSec = d > 1e12 ? (long) (d / 1000.0) : (long) d;
            } else if (v.isTextual()) {
                String s = v.asText().trim();
                if (s.isEmpty()) {
                    return null;
                }
                long raw = Long.parseLong(s);
                epochSec = raw > 1_000_000_000_000L ? raw / 1000 : raw;
            } else {
                return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return LocalDate.ofInstant(Instant.ofEpochSecond(epochSec), ZoneOffset.UTC);
    }

    private static BigDecimal decimalAny(JsonNode n, String... fields) {
        for (String f : fields) {
            if (n == null || !n.has(f) || n.get(f).isNull()) {
                continue;
            }
            JsonNode v = n.get(f);
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
        }
        return null;
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
            return s == null || s.isBlank() ? null : s.trim();
        }
        if (v.isNumber()) {
            return v.asText();
        }
        return null;
    }

    private static String nullIfBlank(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

    private static boolean isUuid(String s) {
        return s != null && UUID_REGEX.matcher(s.trim()).matches();
    }
}
