package zelisline.ub.integrations.csvimport.application;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.application.ItemCreateResult;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.integrations.csvimport.api.dto.CsvImportLineError;
import zelisline.ub.integrations.csvimport.api.dto.CsvImportResponse;
import zelisline.ub.integrations.csvimport.support.CsvImportProgressSink;
import zelisline.ub.integrations.csvimport.support.CsvImportReader;
import zelisline.ub.integrations.csvimport.support.CsvImportReader.SourceRow;
import zelisline.ub.inventory.api.dto.PostOpeningBalanceRequest;
import zelisline.ub.inventory.application.InventoryLedgerService;
import zelisline.ub.pricing.api.dto.PostSellingPriceRequest;
import zelisline.ub.pricing.application.PricingService;
import zelisline.ub.suppliers.api.dto.CreateSupplierRequest;
import zelisline.ub.suppliers.application.SupplierService;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.repository.BranchRepository;

/**
 * Phase 8 Slice 3 — CSV import with dry-run validation and whole-file transactional commits (ADR).
 */
@Service
@RequiredArgsConstructor
public class CsvImportApplicationService {

    private static final BigDecimal QTY_EPS = new BigDecimal("0.00005");

    private final CatalogBootstrapService catalogBootstrapService;
    private final ItemCatalogService itemCatalogService;
    private final ItemRepository itemRepository;
    private final ItemTypeRepository itemTypeRepository;
    private final SupplierService supplierService;
    private final SupplierRepository supplierRepository;
    private final BranchRepository branchRepository;
    private final InventoryLedgerService inventoryLedgerService;
    private final PricingService pricingService;

    public CsvImportResponse dryRunItems(String businessId, byte[] csvBytes) {
        return dryRunItems(businessId, csvBytes, CsvImportProgressSink.NONE);
    }

    public CsvImportResponse dryRunItems(String businessId, byte[] csvBytes, CsvImportProgressSink sink) {
        CsvImportProgressSink s = sink == null ? CsvImportProgressSink.NONE : sink;
        List<SourceRow> rows = readCsv(csvBytes);
        s.onRowsParsed(rows.size());
        List<CsvImportLineError> errors = validateItemRows(businessId, rows);
        s.onRowCommitted(rows.size());
        return new CsvImportResponse(true, rows.size(), errors, null);
    }

    public CsvImportResponse commitItems(String businessId, byte[] csvBytes, String actorUserId) {
        return commitItems(businessId, csvBytes, actorUserId, CsvImportProgressSink.NONE);
    }

    @Transactional
    public CsvImportResponse commitItems(
            String businessId,
            byte[] csvBytes,
            String actorUserId,
            CsvImportProgressSink sink
    ) {
        CsvImportProgressSink s = sink == null ? CsvImportProgressSink.NONE : sink;
        catalogBootstrapService.seedDefaultItemTypesIfMissing(businessId);
        List<SourceRow> rows = readCsv(csvBytes);
        s.onRowsParsed(rows.size());
        List<CsvImportLineError> errors = validateItemRows(businessId, rows);
        if (!errors.isEmpty()) {
            return new CsvImportResponse(false, rows.size(), errors, null);
        }
        LocalDate priceEffective = LocalDate.now(ZoneOffset.UTC);
        int n = 0;
        for (SourceRow sr : rows) {
            Map<String, String> c = sr.columns();
            String sku = col(c, "sku").trim();
            String name = col(c, "name").trim();
            String typeKey = itemTypeKey(c);
            String itemTypeId = itemTypeRepository.findByBusinessIdAndTypeKey(businessId, typeKey)
                    .orElseThrow(() -> new IllegalStateException("missing item type after validation: " + typeKey))
                    .getId();
            String barcodeRaw = col(c, "barcode").trim();
            String barcode = barcodeRaw.isEmpty() ? null : barcodeRaw;
            String unitType = col(c, "unit_type").trim();
            String unitTypeOrNull = unitType.isEmpty() ? null : unitType;
            Boolean stocked = parseNullableTriBool(col(c, "is_stocked"));
            Boolean sellable = parseNullableTriBool(col(c, "is_sellable"));
            BigDecimal reorder = parsePositiveQty(col(c, "reorder_level"));

            CreateItemRequest req = new CreateItemRequest(
                    sku,
                    barcode,
                    name,
                    null,
                    itemTypeId,
                    null,
                    null,
                    unitTypeOrNull,
                    null,
                    sellable,
                    stocked,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    reorder,
                    null,
                    null,
                    null,
                    null
            );
            ItemCreateResult created = itemCatalogService.createItem(businessId, req, null);
            String itemId = created.body().id();

            BigDecimal sellPrice = parseMoney(col(c, "selling_price"));
            if (sellPrice != null && sellPrice.compareTo(BigDecimal.ZERO) > 0) {
                pricingService.setSellingPrice(
                        businessId,
                        new PostSellingPriceRequest(itemId, null, sellPrice, priceEffective, "csv import"),
                        actorUserId
                );
            }
            n++;
            s.onRowCommitted(n);
        }
        return new CsvImportResponse(false, rows.size(), List.of(), n);
    }

    public CsvImportResponse dryRunSuppliers(String businessId, byte[] csvBytes) {
        return dryRunSuppliers(businessId, csvBytes, CsvImportProgressSink.NONE);
    }

    public CsvImportResponse dryRunSuppliers(String businessId, byte[] csvBytes, CsvImportProgressSink sink) {
        CsvImportProgressSink s = sink == null ? CsvImportProgressSink.NONE : sink;
        List<SourceRow> rows = readCsv(csvBytes);
        s.onRowsParsed(rows.size());
        List<CsvImportLineError> errors = validateSupplierRows(businessId, rows);
        s.onRowCommitted(rows.size());
        return new CsvImportResponse(true, rows.size(), errors, null);
    }

    public CsvImportResponse commitSuppliers(String businessId, byte[] csvBytes) {
        return commitSuppliers(businessId, csvBytes, CsvImportProgressSink.NONE);
    }

    @Transactional
    public CsvImportResponse commitSuppliers(String businessId, byte[] csvBytes, CsvImportProgressSink sink) {
        CsvImportProgressSink s = sink == null ? CsvImportProgressSink.NONE : sink;
        List<SourceRow> rows = readCsv(csvBytes);
        s.onRowsParsed(rows.size());
        List<CsvImportLineError> errors = validateSupplierRows(businessId, rows);
        if (!errors.isEmpty()) {
            return new CsvImportResponse(false, rows.size(), errors, null);
        }
        int n = 0;
        for (SourceRow sr : rows) {
            Map<String, String> c = sr.columns();
            String name = col(c, "name").trim();
            String codeRaw = col(c, "code").trim();
            String code = codeRaw.isEmpty() ? null : codeRaw;
            String supplierType = col(c, "supplier_type").trim();
            String vatPin = col(c, "vat_pin").trim();
            String status = col(c, "status").trim();
            String notes = col(c, "notes").trim();
            supplierService.createSupplier(
                    businessId,
                    new CreateSupplierRequest(
                            name,
                            code,
                            supplierType.isEmpty() ? null : supplierType,
                            vatPin.isEmpty() ? null : vatPin,
                            null,
                            null,
                            null,
                            status.isEmpty() ? null : status,
                            notes.isEmpty() ? null : notes,
                            null,
                            null
                    )
            );
            n++;
            s.onRowCommitted(n);
        }
        return new CsvImportResponse(false, rows.size(), List.of(), n);
    }

    public CsvImportResponse dryRunOpeningStock(String businessId, byte[] csvBytes) {
        return dryRunOpeningStock(businessId, csvBytes, CsvImportProgressSink.NONE);
    }

    public CsvImportResponse dryRunOpeningStock(String businessId, byte[] csvBytes, CsvImportProgressSink sink) {
        CsvImportProgressSink s = sink == null ? CsvImportProgressSink.NONE : sink;
        List<SourceRow> rows = readCsv(csvBytes);
        s.onRowsParsed(rows.size());
        List<CsvImportLineError> errors = validateOpeningRows(businessId, rows);
        s.onRowCommitted(rows.size());
        return new CsvImportResponse(true, rows.size(), errors, null);
    }

    public CsvImportResponse commitOpeningStock(String businessId, byte[] csvBytes, String actorUserId) {
        return commitOpeningStock(businessId, csvBytes, actorUserId, CsvImportProgressSink.NONE);
    }

    @Transactional
    public CsvImportResponse commitOpeningStock(
            String businessId,
            byte[] csvBytes,
            String actorUserId,
            CsvImportProgressSink sink
    ) {
        CsvImportProgressSink s = sink == null ? CsvImportProgressSink.NONE : sink;
        List<SourceRow> rows = readCsv(csvBytes);
        s.onRowsParsed(rows.size());
        List<CsvImportLineError> errors = validateOpeningRows(businessId, rows);
        if (!errors.isEmpty()) {
            return new CsvImportResponse(false, rows.size(), errors, null);
        }
        int n = 0;
        for (SourceRow sr : rows) {
            Map<String, String> c = sr.columns();
            Branch branch = resolveBranch(businessId, col(c, "branch_name").trim()).orElseThrow();
            Item item = itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(businessId, col(c, "sku").trim())
                    .orElseThrow();
            BigDecimal qty = parseRequiredQty(col(c, "quantity"));
            BigDecimal unitCost = parseRequiredMoney(col(c, "unit_cost"));
            String notesRaw = col(c, "notes").trim();
            inventoryLedgerService.recordOpeningBalance(
                    businessId,
                    new PostOpeningBalanceRequest(branch.getId(), item.getId(), qty, unitCost, notesRaw.isEmpty() ? null : notesRaw),
                    actorUserId
            );
            n++;
            s.onRowCommitted(n);
        }
        return new CsvImportResponse(false, rows.size(), List.of(), n);
    }

    private List<CsvImportLineError> validateItemRows(String businessId, List<SourceRow> rows) {
        List<CsvImportLineError> errors = new ArrayList<>();
        Set<String> seenSku = new HashSet<>();
        Set<String> seenBarcode = new HashSet<>();
        for (SourceRow sr : rows) {
            Map<String, String> c = sr.columns();
            int line = sr.lineNumber();
            String sku = col(c, "sku").trim();
            String name = col(c, "name").trim();
            if (sku.isEmpty()) {
                errors.add(new CsvImportLineError(line, "sku is required"));
            }
            if (name.isEmpty()) {
                errors.add(new CsvImportLineError(line, "name is required"));
            }
            String typeKey = itemTypeKey(c);
            if (itemTypeRepository.findByBusinessIdAndTypeKey(businessId, typeKey).isEmpty()) {
                errors.add(new CsvImportLineError(line, "unknown item_type_key: " + typeKey));
            }
            if (!sku.isEmpty()) {
                String skuKey = sku.toLowerCase(Locale.ROOT);
                if (!seenSku.add(skuKey)) {
                    errors.add(new CsvImportLineError(line, "duplicate sku in file: " + sku));
                }
                if (itemRepository.existsByBusinessIdAndSkuAndDeletedAtIsNull(businessId, sku)) {
                    errors.add(new CsvImportLineError(line, "sku already exists: " + sku));
                }
            }
            String barcode = col(c, "barcode").trim();
            if (!barcode.isEmpty()) {
                String bcKey = barcode.toLowerCase(Locale.ROOT);
                if (!seenBarcode.add(bcKey)) {
                    errors.add(new CsvImportLineError(line, "duplicate barcode in file: " + barcode));
                }
                Optional<Item> clash = itemRepository.findByBusinessIdAndBarcodeAndDeletedAtIsNull(businessId, barcode);
                if (clash.isPresent()) {
                    errors.add(new CsvImportLineError(line, "barcode already used by sku " + clash.get().getSku()));
                }
            }
            if (parseNullableTriBool(col(c, "is_stocked")) == Boolean.FALSE
                    && parseNullableTriBool(col(c, "is_sellable")) == Boolean.FALSE) {
                errors.add(new CsvImportLineError(line, "item cannot be both not-stocked and not-sellable"));
            }
            BigDecimal reorder = parsePositiveQty(col(c, "reorder_level"));
            if (reorder == null && !col(c, "reorder_level").trim().isEmpty()) {
                errors.add(new CsvImportLineError(line, "invalid reorder_level"));
            }
            BigDecimal sell = parseMoney(col(c, "selling_price"));
            if (sell != null && sell.compareTo(BigDecimal.ZERO) <= 0) {
                errors.add(new CsvImportLineError(line, "selling_price must be > 0 when provided"));
            }
        }
        return errors;
    }

    private List<CsvImportLineError> validateSupplierRows(String businessId, List<SourceRow> rows) {
        List<CsvImportLineError> errors = new ArrayList<>();
        Set<String> seenCode = new HashSet<>();
        Set<String> seenName = new HashSet<>();
        for (SourceRow sr : rows) {
            Map<String, String> c = sr.columns();
            int line = sr.lineNumber();
            String name = col(c, "name").trim();
            if (name.isEmpty()) {
                errors.add(new CsvImportLineError(line, "name is required"));
                continue;
            }
            String nk = name.toLowerCase(Locale.ROOT);
            if (!seenName.add(nk)) {
                errors.add(new CsvImportLineError(line, "duplicate supplier name in file: " + name));
            }
            if (supplierRepository.existsDuplicateName(businessId, name, null)) {
                errors.add(new CsvImportLineError(line, "supplier name already exists: " + name));
            }
            String code = col(c, "code").trim();
            if (!code.isEmpty()) {
                String ck = code.toLowerCase(Locale.ROOT);
                if (!seenCode.add(ck)) {
                    errors.add(new CsvImportLineError(line, "duplicate supplier code in file: " + code));
                }
                supplierRepository.findByBusinessIdAndCodeAndDeletedAtIsNull(businessId, code).ifPresent(s ->
                        errors.add(new CsvImportLineError(line, "supplier code already exists: " + code)));
            }
        }
        return errors;
    }

    private List<CsvImportLineError> validateOpeningRows(String businessId, List<SourceRow> rows) {
        List<CsvImportLineError> errors = new ArrayList<>();
        Set<String> seenPair = new HashSet<>();
        for (SourceRow sr : rows) {
            Map<String, String> c = sr.columns();
            int line = sr.lineNumber();
            String branchName = col(c, "branch_name").trim();
            String sku = col(c, "sku").trim();
            if (branchName.isEmpty()) {
                errors.add(new CsvImportLineError(line, "branch_name is required"));
            }
            if (sku.isEmpty()) {
                errors.add(new CsvImportLineError(line, "sku is required"));
            }
            Optional<Branch> branch = branchName.isEmpty()
                    ? Optional.empty()
                    : resolveBranch(businessId, branchName);
            if (!branchName.isEmpty() && branch.isEmpty()) {
                errors.add(new CsvImportLineError(line, "branch not found: " + branchName));
            }
            Optional<Item> item = sku.isEmpty()
                    ? Optional.empty()
                    : itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(businessId, sku);
            if (!sku.isEmpty() && item.isEmpty()) {
                errors.add(new CsvImportLineError(line, "item sku not found: " + sku));
            }
            item.ifPresent(i -> {
                if (!i.isStocked()) {
                    errors.add(new CsvImportLineError(line, "item is not stocked: " + sku));
                }
                if (i.getCurrentStock() != null && i.getCurrentStock().abs().compareTo(QTY_EPS) > 0) {
                    errors.add(new CsvImportLineError(line,
                            "item already has on-hand stock; opening balance refused for sku " + sku));
                }
            });
            BigDecimal qty = parseRequiredQty(col(c, "quantity"));
            if (qty == null) {
                errors.add(new CsvImportLineError(line, "quantity must be a number > 0"));
            }
            BigDecimal unitCost = parseRequiredMoney(col(c, "unit_cost"));
            if (unitCost == null) {
                errors.add(new CsvImportLineError(line, "unit_cost must be a number > 0"));
            }
            if (branch.isPresent() && !sku.isEmpty()) {
                String key = branch.get().getId() + "|" + sku.toLowerCase(Locale.ROOT);
                if (!seenPair.add(key)) {
                    errors.add(new CsvImportLineError(line, "duplicate branch_name + sku in file"));
                }
            }
        }
        return errors;
    }

    private Optional<Branch> resolveBranch(String businessId, String branchName) {
        return branchRepository.findByBusinessIdAndDeletedAtIsNullOrderByNameAsc(businessId).stream()
                .filter(b -> b.getName().trim().equalsIgnoreCase(branchName.trim()))
                .findFirst();
    }

    private static List<SourceRow> readCsv(byte[] csvBytes) {
        try {
            return CsvImportReader.readRows(new ByteArrayInputStream(csvBytes));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not parse CSV: " + e.getMessage());
        }
    }

    private static String itemTypeKey(Map<String, String> cols) {
        String raw = col(cols, "item_type_key").trim().toLowerCase(Locale.ROOT);
        return raw.isEmpty() ? "goods" : raw;
    }

    private static String col(Map<String, String> cols, String key) {
        return cols.getOrDefault(key, "");
    }

    private static Boolean parseNullableTriBool(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "true", "yes", "y", "1" -> Boolean.TRUE;
            case "false", "no", "n", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static BigDecimal parseMoney(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(raw.trim()).setScale(4, RoundingMode.HALF_UP);
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal parseRequiredMoney(String raw) {
        BigDecimal v = parseMoney(raw);
        if (v == null) {
            return null;
        }
        return v.compareTo(BigDecimal.ZERO) > 0 ? v.setScale(4, RoundingMode.HALF_UP) : null;
    }

    private static BigDecimal parseRequiredQty(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(raw.trim()).setScale(4, RoundingMode.HALF_UP);
            return v.compareTo(QTY_EPS) > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Accepts blank → {@code null}; otherwise must be ≥ 0. */
    private static BigDecimal parsePositiveQty(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(raw.trim()).setScale(4, RoundingMode.HALF_UP);
            if (v.compareTo(BigDecimal.ZERO) < 0) {
                return null;
            }
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
