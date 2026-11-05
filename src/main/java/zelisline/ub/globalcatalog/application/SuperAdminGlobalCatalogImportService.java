package zelisline.ub.globalcatalog.application;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.ImportColumnsResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.ImportCommitResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.ImportIssueResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.ImportPackPlanResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.ImportPackResultResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.ImportParentPlanResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.ImportPreviewResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.ImportRowPlanResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.ImportRowResultResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.ImportSummaryResponse;
import zelisline.ub.globalcatalog.domain.GlobalCatalog;
import zelisline.ub.globalcatalog.domain.GlobalCategory;
import zelisline.ub.globalcatalog.domain.GlobalProduct;
import zelisline.ub.globalcatalog.domain.GlobalProductPack;
import zelisline.ub.globalcatalog.domain.GlobalProductStatus;
import zelisline.ub.globalcatalog.repository.GlobalCatalogRepository;
import zelisline.ub.globalcatalog.repository.GlobalCategoryRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductPackItemRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductPackRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductRepository;

/**
 * Global-catalog import v2 (GLOBAL_CATALOG_IMPORT_SCOPE, P0+P1): parse (CSV or xlsx) →
 * normalise → <b>plan</b> → (preview stops here) → commit, each row in its own transaction,
 * with the parent link written inside the child's transaction via
 * {@link GlobalCatalogProductGraphWriter}.
 *
 * <p>File modes (§5.1): plain products; products+parents (parent keys, no {@code row_type});
 * manifest ({@code row_type} = product | pack | pack_item). A file with pack columns and no
 * {@code row_type} fails with {@code file.row_type_required} — it never degrades silently.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuperAdminGlobalCatalogImportService {

    public static final int MAX_IMPORT_ROWS = 5_000;

    private static final String DEFAULT_CATALOG_CODE = "default";

    static final String SEV_ERROR = "error";
    static final String SEV_WARNING = "warning";
    static final String SEV_INFO = "info";

    static final String MODE_PRODUCTS = "products";
    static final String MODE_PRODUCTS_PARENTS = "products+parents";
    static final String MODE_MANIFEST = "manifest";

    private static final Set<String> PRODUCT_HEADERS = Set.of(
            "row_type", "ref", "id", "name", "brand", "size", "description", "barcode",
            "sku_template", "unit_type", "weighed", "sellable", "stocked", "status",
            "variant_name", "is_package_variant",
            "parent_ref", "parent_id", "parent_barcode", "parent_sku",
            "parent_name", "parent_status", "parent_store_kit_id",
            "packaging_unit_name", "packaging_unit_qty", "store_kit_id",
            "global_category_id", "category_name", "category_slug",
            "recommended_buying_price", "recommended_selling_price", "suggested_margin_pct",
            "default_reorder_level", "default_reorder_qty", "default_min_stock_level",
            "has_expiry", "expires_after_days", "item_type_key_hint", "sort_order",
            "version", "action", "image_url");

    private static final Set<String> PACK_HEADERS = Set.of(
            "pack_code", "pack_name", "pack_description", "pack_store_kit_id",
            "pack_status", "pack_sort_order", "pack_replace");

    private static final Set<String> PACK_ITEM_HEADERS = Set.of(
            "pack_ref", "product_ref", "product_barcode", "product_sku", "product_id",
            "item_sort_order");

    private static final Set<String> KNOWN_HEADERS;

    static {
        Set<String> all = new HashSet<>(PRODUCT_HEADERS);
        all.addAll(PACK_HEADERS);
        all.addAll(PACK_ITEM_HEADERS);
        KNOWN_HEADERS = Set.copyOf(all);
    }

    /** Legacy / Palmart-export header aliases (§5.6) — mapped with a visible warning. */
    private static final Map<String, String> HEADER_ALIASES = Map.ofEntries(
            Map.entry("sku", "sku_template"),
            Map.entry("selling_price", "recommended_selling_price"),
            Map.entry("sell_price", "recommended_selling_price"),
            Map.entry("buying_price", "recommended_buying_price"),
            Map.entry("buy_price", "recommended_buying_price"),
            Map.entry("cost_price", "recommended_buying_price"),
            Map.entry("min_stock_level", "default_min_stock_level"),
            Map.entry("reorder_level", "default_reorder_level"),
            Map.entry("reorder_qty", "default_reorder_qty"),
            Map.entry("category", "category_name"),
            Map.entry("item_type_key", "item_type_key_hint"),
            Map.entry("is_stocked", "stocked"),
            Map.entry("is_sellable", "sellable"),
            Map.entry("is_weighed", "weighed"),
            Map.entry("variant_of_sku", "parent_sku"),
            /** Also implies {@code is_package_variant=true} on rows where it is filled. */
            Map.entry("pack_of_sku", "parent_sku"),
            Map.entry("pack_qty", "packaging_unit_qty"),
            Map.entry("units_per_pack", "packaging_unit_qty"));

    private static final Set<String> EXPLICIT_IGNORES = Set.of(
            "on_hand", "aisle_code", "supplier_name", "supplier_code");

    private static final Set<String> PARENT_KEY_HEADERS = Set.of(
            "parent_ref", "parent_id", "parent_barcode", "parent_sku");

    private static final Set<String> ROW_TYPE_REQUIRED_HEADERS = Set.of(
            "pack_code", "pack_ref", "product_ref");

    private static final Set<String> UNIT_TYPES = Set.of("each", "kg", "g", "l", "ml", "pack");

    /** Verticals understood by tenant scoping (GlobalCatalogService.resolveStoreKitScope). */
    private static final Set<String> KNOWN_STORE_KITS = Set.of(
            "grocery", "pharmacy", "cosmetics", "wines-spirits");

    private static final Set<String> TRUE_TOKENS = Set.of("1", "true", "yes", "y");
    private static final Set<String> FALSE_TOKENS = Set.of("0", "false", "no", "n");

    /** Nullable text columns where a blank cell means "clear to null" on update (§5.7). */
    private static final Set<String> CLEARABLE_TEXT = Set.of(
            "brand", "size", "description", "barcode", "sku_template",
            "variant_name", "packaging_unit_name");

    private static final java.util.regex.Pattern REF_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z0-9._:-]{1,191}");

    private final GlobalCatalogRepository globalCatalogRepository;
    private final GlobalCategoryRepository globalCategoryRepository;
    private final GlobalProductRepository globalProductRepository;
    private final GlobalProductPackRepository globalProductPackRepository;
    private final GlobalProductPackItemRepository globalProductPackItemRepository;
    private final GlobalCatalogProductGraphWriter graphWriter;
    private final SuperAdminGlobalCatalogImportLineExecutor lineExecutor;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    public record ImportOptions(
            String defaultStatus,
            boolean createCategories,
            boolean reviveArchived,
            boolean force,
            boolean allowDraftParent,
            String defaultStoreKitId
    ) {
        public ImportOptions {
            defaultStatus = defaultStatus == null || defaultStatus.isBlank()
                    ? GlobalProductStatus.DRAFT
                    : GlobalProductStatus.normalize(defaultStatus);
            defaultStoreKitId = defaultStoreKitId == null || defaultStoreKitId.isBlank()
                    ? "grocery"
                    : defaultStoreKitId.trim();
        }
    }

    // ================================================================= preview / commit

    @Transactional(readOnly = true)
    public ImportPreviewResponse preview(String catalogId, MultipartFile file, ImportOptions options) {
        GlobalCatalog catalog = requireCatalog(catalogId);
        Plan plan = buildPlan(catalog, file, options);
        return toPreviewResponse(plan);
    }

    @Transactional
    public ImportCommitResponse commit(String catalogId, MultipartFile file, ImportOptions options) {
        GlobalCatalog catalog = requireCatalog(catalogId);
        globalCatalogRepository.lockById(catalog.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Global catalog not found"));

        Plan plan = buildPlan(catalog, file, options);
        if (plan.hasBlockingFileIssues()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    plan.fileIssues.stream()
                            .filter(i -> SEV_ERROR.equals(i.severity()))
                            .map(Issue::message)
                            .findFirst()
                            .orElse("File rejected"));
        }

        createPlannedCategories(catalog.getId(), plan);

        Set<String> failedProductIds = new HashSet<>();
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int skipped = 0;
        int errors = 0;
        int blocked = 0;

        // 1. Synthetic parents first — children reference their planned ids.
        for (SyntheticParent synthetic : plan.syntheticParents.values()) {
            try {
                lineExecutor.apply(catalog.getId(), null, synthetic.plannedId,
                        synthetic.values, options.allowDraftParent());
                created++;
            } catch (Exception ex) {
                failedProductIds.add(synthetic.plannedId);
                plan.fileIssues.add(new Issue("relation.parent_create_failed", SEV_WARNING,
                        "Could not create parent '" + synthetic.name + "': " + rootMessage(ex), null));
            }
        }

        // 2. Product rows: parents (no link) before children (depth ≤ 1 ⇒ two buckets suffice).
        List<RowPlan> ordered = new ArrayList<>(plan.rows);
        ordered.sort((a, b) -> Boolean.compare(
                a.values.containsKey(ImportProductField.VARIANT_OF),
                b.values.containsKey(ImportProductField.VARIANT_OF)));

        Map<Integer, ImportRowResultResponse> resultsByLine = new LinkedHashMap<>();
        for (RowPlan row : ordered) {
            switch (row.action) {
                case "error" -> {
                    errors++;
                    resultsByLine.put(row.line, rowResult(row, "error", null));
                }
                case "blocked" -> {
                    blocked++;
                    resultsByLine.put(row.line, rowResult(row, "blocked", null));
                }
                case "skip" -> {
                    skipped++;
                    resultsByLine.put(row.line, rowResult(row, "skipped",
                            row.existing == null ? null : row.existing.getId()));
                }
                case "unchanged" -> {
                    unchanged++;
                    resultsByLine.put(row.line, rowResult(row, "unchanged", row.existing.getId()));
                }
                default -> {
                    Object parentId = row.values.get(ImportProductField.VARIANT_OF);
                    if (parentId instanceof String pid && failedProductIds.contains(pid)) {
                        blocked++;
                        row.issues.add(new Issue("relation.parent_blocked", SEV_ERROR,
                                "Parent row failed earlier in this run", null));
                        resultsByLine.put(row.line, rowResult(row, "blocked", null));
                        continue;
                    }
                    try {
                        var outcome = lineExecutor.apply(
                                catalog.getId(),
                                row.existing == null ? null : row.existing.getId(),
                                row.plannedId,
                                row.values,
                                options.allowDraftParent());
                        if (outcome.created()) {
                            created++;
                            resultsByLine.put(row.line, rowResult(row, "created", outcome.productId()));
                        } else {
                            updated++;
                            resultsByLine.put(row.line, rowResult(row, "updated", outcome.productId()));
                        }
                    } catch (Exception ex) {
                        errors++;
                        if (row.plannedId != null) {
                            failedProductIds.add(row.plannedId);
                        }
                        if (row.existing != null) {
                            failedProductIds.add(row.existing.getId());
                        }
                        row.issues.add(new Issue("product.write_failed", SEV_ERROR, rootMessage(ex), null));
                        resultsByLine.put(row.line, rowResult(row, "error", null));
                    }
                }
            }
        }
        // Report in file order, not commit order.
        List<ImportRowResultResponse> results = plan.rows.stream()
                .map(r -> resultsByLine.get(r.line))
                .filter(Objects::nonNull)
                .toList();

        // 3. Packs + membership (each pack in its own transaction).
        List<ImportPackResultResponse> packResults = new ArrayList<>();
        for (PackPlan pack : plan.packs) {
            if ("error".equals(pack.action)) {
                packResults.add(packResult(pack, "error", null, 0, 0));
                continue;
            }
            List<String> memberIds = new ArrayList<>();
            for (PackItemPlan item : pack.items) {
                if (item.resolvedProductId == null) {
                    continue;
                }
                if (failedProductIds.contains(item.resolvedProductId)) {
                    pack.issues.add(new Issue("pack.member_blocked", SEV_WARNING,
                            "Line " + item.line + ": member skipped (its product row failed)", null));
                    continue;
                }
                memberIds.add(item.resolvedProductId);
            }
            try {
                var outcome = lineExecutor.applyPack(
                        catalog.getId(),
                        pack.existing == null ? null : pack.existing.getId(),
                        pack.toValues(),
                        pack.replace,
                        memberIds);
                packResults.add(packResult(pack, outcome.created() ? "created" : "updated",
                        outcome.packId(), outcome.membersAdded(), outcome.membersRemoved()));
            } catch (Exception ex) {
                pack.issues.add(new Issue("pack.write_failed", SEV_ERROR, rootMessage(ex), null));
                packResults.add(packResult(pack, "error", null, 0, 0));
            }
        }

        ImportSummaryResponse summary = new ImportSummaryResponse(
                created, updated, unchanged, skipped, errors, (int) countWarnings(plan),
                plan.willPublish, plan.willStayDraft,
                blocked, plan.parentLinks, plan.packMemberships, plan.syntheticParents.size(),
                plan.packCreate, plan.packUpdate);
        publishAudit(catalog, plan, summary);
        return new ImportCommitResponse(plan.fileName, summary, results, packResults,
                plan.fileIssues.stream().map(this::toIssueResponse).toList());
    }

    // ================================================================= template

    public byte[] templateCsv(String mode) {
        String normalized = mode == null || mode.isBlank() ? "products" : mode.trim().toLowerCase(Locale.ROOT);
        if ("manifest".equals(normalized)) {
            String header = String.join(",",
                    "row_type", "ref", "name", "barcode", "parent_ref", "variant_name",
                    "is_package_variant", "packaging_unit_name", "packaging_unit_qty",
                    "category_name", "status", "recommended_buying_price", "recommended_selling_price",
                    "store_kit_id",
                    "pack_code", "pack_name", "pack_status", "pack_replace",
                    "pack_ref", "product_ref", "item_sort_order");
            List<String> rows = List.of(
                    "product,COKE-500,Coca-Cola 500ml,5449000000996,,,,,,Drinks,published,60,80,grocery,,,,,,,",
                    "product,COKE-500-TRAY,Coca-Cola 500ml Tray,5449000001009,COKE-500,Tray,true,Tray,12,Drinks,published,700,900,grocery,,,,,,,",
                    "product,COKE-500-CRATE,Coca-Cola 500ml Crate,5449000001016,COKE-500,Crate,true,Crate,24,Drinks,published,1400,1750,grocery,,,,,,,",
                    "pack,MM-STARTER,,,,,,,,,,,,,MM-STARTER,Mini-mart starter pack,published,true,,,",
                    "pack_item,,,,,,,,,,,,,,,,,,MM-STARTER,COKE-500,0",
                    "pack_item,,,,,,,,,,,,,,,,,,MM-STARTER,COKE-500-TRAY,1");
            return ("﻿" + header + "\r\n" + String.join("\r\n", rows) + "\r\n")
                    .getBytes(StandardCharsets.UTF_8);
        }
        if (!"products".equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown template mode '" + normalized + "' (products|manifest)");
        }
        String header = String.join(",",
                "name", "brand", "size", "barcode", "sku_template", "unit_type",
                "weighed", "sellable", "stocked", "status",
                "category_name", "recommended_buying_price", "recommended_selling_price",
                "suggested_margin_pct", "default_reorder_level", "default_reorder_qty",
                "default_min_stock_level", "has_expiry", "expires_after_days",
                "item_type_key_hint", "sort_order", "description", "action");
        String example1 = "Coca-Cola 500ml,Coca-Cola,500ml,5449000000996,,each,false,true,true,published,"
                + "Drinks,60,80,33.33,,,,false,,goods,0,Chilled soft drink,upsert";
        String example2 = "Pishori Rice 1kg,,1kg,,RICE-PISH-1KG,kg,true,true,true,draft,"
                + "Grains & Cereals,150,180,20,5,10,2,false,,goods,0,,upsert";
        return ("﻿" + header + "\r\n" + example1 + "\r\n" + example2 + "\r\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    // ================================================================= plan model

    static final class Issue {
        private final String code;
        private final String severity;
        private final String message;
        private final String field;

        Issue(String code, String severity, String message, String field) {
            this.code = code;
            this.severity = severity;
            this.message = message;
            this.field = field;
        }

        String code() {
            return code;
        }

        String severity() {
            return severity;
        }

        String message() {
            return message;
        }

        String field() {
            return field;
        }
    }

    static final class ParentPlan {
        String key;          // the value the file used (ref / id / barcode / sku / name)
        String resolution;   // in_file | existing | synthetic
        String plannedId;
        String state;        // will_create | exists | will_stay_draft | published
    }

    static final class RowPlan {
        int line;
        String rowType = "product";
        String ref;
        String action = "create"; // create | update | unchanged | skip | blocked | error
        String requestedAction = "upsert";
        GlobalProduct existing;
        String plannedId;         // pre-generated UUID for creates (referenceable by children)
        boolean revived;
        boolean stockedExplicit;
        /** Set in pass A from the raw cells, so depth checks don't depend on processing order. */
        boolean hasParentKeys;
        boolean packageVariantImplied; // via pack_of_sku alias
        String name;
        String barcode;
        ParentPlan parent;
        final Map<ImportProductField, Object> values = new EnumMap<>(ImportProductField.class);
        final Map<String, List<String>> diff = new LinkedHashMap<>();
        final List<Issue> issues = new ArrayList<>();

        boolean hasError() {
            return issues.stream().anyMatch(i -> SEV_ERROR.equals(i.severity()));
        }
    }

    static final class PackPlan {
        int line;
        String ref;
        String code;
        String name;
        String description;
        boolean descriptionPresent;
        String storeKitId;
        boolean storeKitPresent;
        String status;
        Integer sortOrder;
        boolean replace;
        String action = "create"; // create | update | error
        GlobalProductPack existing;
        String plannedId;
        final List<PackItemPlan> items = new ArrayList<>();
        final List<Issue> issues = new ArrayList<>();

        boolean hasError() {
            return issues.stream().anyMatch(i -> SEV_ERROR.equals(i.severity()));
        }

        SuperAdminGlobalCatalogImportLineExecutor.PackValues toValues() {
            return new SuperAdminGlobalCatalogImportLineExecutor.PackValues(
                    code, name, description, storeKitId, status, sortOrder,
                    descriptionPresent, storeKitPresent);
        }
    }

    static final class PackItemPlan {
        int line;
        String packRef;
        String resolvedProductId;
        Integer sortOrder;
    }

    static final class SyntheticParent {
        String plannedId;
        String name;
        final Map<ImportProductField, Object> values = new EnumMap<>(ImportProductField.class);
    }

    static final class Plan {
        String fileName;
        String mode = MODE_PRODUCTS;
        final Map<String, Integer> rowTypeCounts = new LinkedHashMap<>();
        final List<RowPlan> rows = new ArrayList<>();
        final List<PackPlan> packs = new ArrayList<>();
        final List<Issue> fileIssues = new ArrayList<>();
        final List<String> mappedColumns = new ArrayList<>();
        final List<String> aliasedColumns = new ArrayList<>();
        final List<String> ignoredColumns = new ArrayList<>();
        final List<String> unknownColumns = new ArrayList<>();
        final Map<String, PlannedCategory> plannedCategories = new LinkedHashMap<>();
        final Map<String, SyntheticParent> syntheticParents = new LinkedHashMap<>();
        int willPublish;
        int willStayDraft;
        int parentLinks;
        int packMemberships;
        int packCreate;
        int packUpdate;

        boolean hasBlockingFileIssues() {
            return fileIssues.stream().anyMatch(i -> SEV_ERROR.equals(i.severity()));
        }
    }

    record PlannedCategory(String id, String name) {
    }

    // ================================================================= planning

    private Plan buildPlan(GlobalCatalog catalog, MultipartFile file, ImportOptions options) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty file");
        }
        Plan plan = new Plan();
        plan.fileName = file.getOriginalFilename() == null ? "import.csv" : file.getOriginalFilename();

        ParsedFile parsed;
        try {
            parsed = parseFile(file.getBytes(), plan);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read file: " + ex.getMessage());
        }
        if (plan.hasBlockingFileIssues()) {
            return plan;
        }
        if (parsed.rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File has a header but no data rows");
        }
        if (parsed.rows.size() > MAX_IMPORT_ROWS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "File exceeds " + MAX_IMPORT_ROWS + " data rows (" + parsed.rows.size() + ")");
        }

        detectMode(parsed, plan);
        if (plan.hasBlockingFileIssues()) {
            return plan;
        }

        Catalogs catalogs = loadCatalogState(catalog.getId());

        // ---- split rows by type ----
        List<RawRow> productRows = new ArrayList<>();
        List<RawRow> packRows = new ArrayList<>();
        List<RawRow> packItemRows = new ArrayList<>();
        for (RawRow raw : parsed.rows) {
            String rowType = MODE_MANIFEST.equals(plan.mode)
                    ? normalizeRowType(raw.cell("row_type"))
                    : "product";
            switch (rowType == null ? "product" : rowType) {
                case "product" -> productRows.add(raw);
                case "pack" -> packRows.add(raw);
                case "pack_item" -> packItemRows.add(raw);
                default -> {
                    RowPlan bad = new RowPlan();
                    bad.line = raw.line;
                    bad.rowType = raw.cell("row_type");
                    bad.action = "error";
                    bad.issues.add(new Issue("file.unknown_row_type", SEV_ERROR,
                            "Unknown row_type '" + raw.cell("row_type") + "' (product|pack|pack_item)",
                            "row_type"));
                    plan.rows.add(bad);
                }
            }
            String key = rowType == null ? "product" : rowType;
            plan.rowTypeCounts.merge(key, 1, Integer::sum);
        }

        // ---- refs: validate + uniqueness across the whole file ----
        Map<String, RawRow> byRef = new HashMap<>();
        Map<String, List<Integer>> refLines = new HashMap<>();
        for (RawRow raw : parsed.rows) {
            String ref = blankToNull(raw.cell("ref"));
            if (ref != null) {
                refLines.computeIfAbsent(ref, k -> new ArrayList<>()).add(raw.line);
                byRef.putIfAbsent(ref, raw);
            }
        }

        // ---- in-file duplicate keys + parent lookup indexes ----
        Map<String, List<Integer>> fileBarcodes = new HashMap<>();
        Map<String, List<Integer>> fileSkus = new HashMap<>();
        Map<String, RawRow> fileByBarcode = new HashMap<>();
        Map<String, RawRow> fileBySku = new HashMap<>();
        Map<String, RawRow> fileById = new HashMap<>();
        for (RawRow raw : productRows) {
            String barcode = blankToNull(raw.cell("barcode"));
            if (barcode != null) {
                fileBarcodes.computeIfAbsent(barcode, k -> new ArrayList<>()).add(raw.line);
                fileByBarcode.putIfAbsent(barcode, raw);
            }
            String sku = blankToNull(raw.cell("sku_template"));
            if (sku != null) {
                fileSkus.computeIfAbsent(sku.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(raw.line);
                fileBySku.putIfAbsent(sku.toLowerCase(Locale.ROOT), raw);
            }
            String id = blankToNull(raw.cell("id"));
            if (id != null) {
                fileById.putIfAbsent(id, raw);
            }
        }

        // ---- pass A: per-product-row planning (fields + identity; parents later) ----
        boolean relationsActive = !MODE_PRODUCTS.equals(plan.mode);
        Map<Integer, RowPlan> planByLine = new LinkedHashMap<>();
        Map<String, RowPlan> planByRef = new HashMap<>();
        Map<String, RowPlan> planByMatchedId = new HashMap<>();
        for (RawRow raw : productRows) {
            RowPlan row = planProductRow(raw, catalogs, options, plan, fileBarcodes, fileSkus,
                    relationsActive, refLines);
            planByLine.put(raw.line, row);
            plan.rows.add(row);
            if (row.ref != null) {
                planByRef.put(row.ref, row);
            }
            if (row.existing != null) {
                planByMatchedId.putIfAbsent(row.existing.getId(), row);
            }
        }

        // ---- pass B: parent resolution + classification ----
        for (RawRow raw : productRows) {
            RowPlan row = planByLine.get(raw.line);
            if (relationsActive) {
                resolveParent(raw, row, planByRef, planByLine, fileById, fileByBarcode, fileBySku,
                        planByMatchedId, catalogs, options, plan);
            }
            classifyRow(row, options);
            if ("create".equals(row.action) || "update".equals(row.action)) {
                String finalStatus = finalStatus(row, options);
                if (GlobalProductStatus.PUBLISHED.equals(finalStatus)) {
                    plan.willPublish++;
                } else if (GlobalProductStatus.DRAFT.equals(finalStatus)) {
                    plan.willStayDraft++;
                }
                if (row.values.get(ImportProductField.VARIANT_OF) != null) {
                    plan.parentLinks++;
                }
            }
        }

        // ---- packs ----
        Map<String, PackPlan> packByRef = new HashMap<>();
        Map<String, List<Integer>> packCodeLines = new HashMap<>();
        for (RawRow raw : packRows) {
            String code = blankToNull(raw.cell("pack_code"));
            if (code != null) {
                packCodeLines.computeIfAbsent(code.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(raw.line);
            }
        }
        for (RawRow raw : packRows) {
            PackPlan pack = planPackRow(raw, catalogs, options, packCodeLines, refLines);
            plan.packs.add(pack);
            if (pack.ref != null) {
                packByRef.put(pack.ref, pack);
            }
            if ("create".equals(pack.action)) {
                plan.packCreate++;
            } else if ("update".equals(pack.action)) {
                plan.packUpdate++;
            }
        }

        // ---- pack items ----
        for (RawRow raw : packItemRows) {
            planPackItemRow(raw, packByRef, planByRef, fileByBarcode, fileBySku, fileById,
                    planByLine, catalogs, plan);
        }
        for (PackPlan pack : plan.packs) {
            plan.packMemberships += pack.items.size();
            if (pack.replace && pack.existing != null) {
                int existingCount = globalProductPackItemRepository
                        .findByPackIdOrderBySortOrderAsc(pack.existing.getId()).size();
                if (pack.items.size() < existingCount) {
                    pack.issues.add(new Issue("pack.members_would_be_removed", SEV_WARNING,
                            "Replace will shrink '" + pack.code + "' from " + existingCount
                                    + " to " + pack.items.size() + " members", "pack_replace"));
                }
            }
        }
        return plan;
    }

    private RowPlan planProductRow(
            RawRow raw,
            Catalogs catalogs,
            ImportOptions options,
            Plan plan,
            Map<String, List<Integer>> fileBarcodes,
            Map<String, List<Integer>> fileSkus,
            boolean relationsActive,
            Map<String, List<Integer>> refLines
    ) {
        RowPlan row = new RowPlan();
        row.line = raw.line;
        row.rowType = "product";

        String ref = blankToNull(raw.cell("ref"));
        if (ref != null) {
            if (!REF_PATTERN.matcher(ref).matches()) {
                row.issues.add(new Issue("file.invalid_ref", SEV_ERROR,
                        "ref may only use letters, digits, . _ : - (max 191)", "ref"));
            } else if (refLines.getOrDefault(ref, List.of()).size() > 1) {
                row.issues.add(new Issue("file.duplicate_ref", SEV_ERROR,
                        "ref '" + ref + "' appears on lines " + refLines.get(ref), "ref"));
            } else {
                row.ref = ref;
            }
        }

        String name = blankToNull(raw.cell("name"));
        row.name = name;
        if (name == null) {
            row.issues.add(new Issue("product.name_required", SEV_ERROR, "name is required", "name"));
        } else if (name.length() > 500) {
            row.issues.add(new Issue("product.invalid_name", SEV_ERROR,
                    "name is longer than 500 characters", "name"));
        }

        String barcode = blankToNull(raw.cell("barcode"));
        row.barcode = barcode;
        if (barcode != null && fileBarcodes.getOrDefault(barcode, List.of()).size() > 1) {
            row.issues.add(new Issue("product.barcode_duplicate_in_file", SEV_ERROR,
                    "Barcode " + barcode + " appears on lines " + fileBarcodes.get(barcode), "barcode"));
        }
        String sku = blankToNull(raw.cell("sku_template"));
        if (sku != null && fileSkus.getOrDefault(sku.toLowerCase(Locale.ROOT), List.of()).size() > 1) {
            row.issues.add(new Issue("product.sku_duplicate_in_file", SEV_WARNING,
                    "sku_template " + sku + " appears on lines "
                            + fileSkus.get(sku.toLowerCase(Locale.ROOT)), "sku_template"));
        }

        row.hasParentKeys = blankToNull(raw.cell("parent_ref")) != null
                || blankToNull(raw.cell("parent_id")) != null
                || blankToNull(raw.cell("parent_barcode")) != null
                || blankToNull(raw.cell("parent_sku")) != null;

        resolveIdentity(row, raw, catalogs, options);
        boolean creating = row.existing == null;
        if (creating) {
            row.plannedId = UUID.randomUUID().toString();
        }

        row.requestedAction = normalizeAction(raw.cell("action"), row);
        if ("skip".equals(row.requestedAction)) {
            return row;
        }
        if ("create".equals(row.requestedAction) && !creating) {
            row.issues.add(new Issue("product.already_exists", SEV_ERROR,
                    "action=create but a matching product exists (" + row.existing.getId() + ")", "action"));
        }
        if ("update".equals(row.requestedAction) && creating) {
            row.issues.add(new Issue("product.not_found", SEV_ERROR,
                    "action=update but no product matches this row", "action"));
        }

        checkVersion(row, raw, options);

        // ---- flat assignments (absent column vs blank cell, §5.7) ----
        assignText(row, raw, "name", ImportProductField.NAME, false);
        assignText(row, raw, "brand", ImportProductField.BRAND, true);
        assignText(row, raw, "size", ImportProductField.SIZE, true);
        String sizeCell = blankToNull(raw.cell("size"));
        if (sizeCell != null && sizeCell.length() > 50) {
            row.issues.add(new Issue("product.invalid_size", SEV_ERROR,
                    "size is longer than 50 characters", "size"));
        }
        assignText(row, raw, "description", ImportProductField.DESCRIPTION, true);
        assignText(row, raw, "barcode", ImportProductField.BARCODE, true);
        assignText(row, raw, "sku_template", ImportProductField.SKU_TEMPLATE, true);
        assignText(row, raw, "variant_name", ImportProductField.VARIANT_NAME, true);
        assignText(row, raw, "packaging_unit_name", ImportProductField.PACKAGING_UNIT_NAME, true);

        assignUnitType(row, raw, creating);
        assignBoolean(row, raw, "weighed", ImportProductField.WEIGHED, creating, false);
        assignBoolean(row, raw, "sellable", ImportProductField.SELLABLE, creating, true);
        row.stockedExplicit = blankToNull(raw.cell("stocked")) != null;
        assignBoolean(row, raw, "stocked", ImportProductField.STOCKED, creating, true);
        assignBoolean(row, raw, "is_package_variant", ImportProductField.PACKAGE_VARIANT, creating, false);
        // pack_of_sku alias: a filled cell implies a package variant unless the file says otherwise.
        if (raw.impliedPackageVariant() && !row.values.containsKey(ImportProductField.PACKAGE_VARIANT)) {
            row.values.put(ImportProductField.PACKAGE_VARIANT, Boolean.TRUE);
            row.packageVariantImplied = true;
        } else if (raw.impliedPackageVariant()
                && Boolean.FALSE.equals(row.values.get(ImportProductField.PACKAGE_VARIANT)) && creating) {
            row.values.put(ImportProductField.PACKAGE_VARIANT, Boolean.TRUE);
            row.packageVariantImplied = true;
        }
        assignStatus(row, raw, creating, options);
        assignCategory(row, raw, catalogs, options, plan, creating);
        assignStoreKit(row, raw, creating, options);

        assignDecimal(row, raw, "recommended_buying_price", ImportProductField.BUYING_PRICE,
                BigDecimal.ZERO, null, 2);
        assignDecimal(row, raw, "recommended_selling_price", ImportProductField.SELLING_PRICE,
                BigDecimal.ZERO, null, 2);
        assignDecimal(row, raw, "suggested_margin_pct", ImportProductField.MARGIN_PCT,
                BigDecimal.ZERO, new BigDecimal("999.99"), 2);
        assignDecimal(row, raw, "default_reorder_level", ImportProductField.REORDER_LEVEL,
                BigDecimal.ZERO, null, 4);
        assignDecimal(row, raw, "default_reorder_qty", ImportProductField.REORDER_QTY,
                BigDecimal.ZERO, null, 4);
        assignDecimal(row, raw, "default_min_stock_level", ImportProductField.MIN_STOCK_LEVEL,
                BigDecimal.ZERO, null, 4);
        assignDecimal(row, raw, "packaging_unit_qty", ImportProductField.PACKAGING_UNIT_QTY,
                new BigDecimal("0.0001"), null, 4);

        assignBoolean(row, raw, "has_expiry", ImportProductField.HAS_EXPIRY, creating, false);
        assignInteger(row, raw, "expires_after_days", ImportProductField.EXPIRES_AFTER_DAYS);
        assignText(row, raw, "item_type_key_hint", ImportProductField.ITEM_TYPE_KEY_HINT, false);
        if (creating && !row.values.containsKey(ImportProductField.ITEM_TYPE_KEY_HINT)) {
            row.values.put(ImportProductField.ITEM_TYPE_KEY_HINT, "goods");
        }
        assignInteger(row, raw, "sort_order", ImportProductField.SORT_ORDER);
        if (creating && !row.values.containsKey(ImportProductField.SORT_ORDER)) {
            row.values.put(ImportProductField.SORT_ORDER, 0);
        }

        // Package variant requires packaging name + qty (from file or stored).
        boolean packageVariant = Boolean.TRUE.equals(row.values.get(ImportProductField.PACKAGE_VARIANT))
                || (!creating && !row.values.containsKey(ImportProductField.PACKAGE_VARIANT)
                        && row.existing.isPackageVariant());
        if (packageVariant) {
            Object unitName = row.values.containsKey(ImportProductField.PACKAGING_UNIT_NAME)
                    ? row.values.get(ImportProductField.PACKAGING_UNIT_NAME)
                    : (creating ? null : row.existing.getPackagingUnitName());
            Object unitQty = row.values.containsKey(ImportProductField.PACKAGING_UNIT_QTY)
                    ? row.values.get(ImportProductField.PACKAGING_UNIT_QTY)
                    : (creating ? null : row.existing.getPackagingUnitQty());
            if (unitName == null || unitQty == null) {
                row.issues.add(new Issue("product.packaging_required", SEV_ERROR,
                        "is_package_variant=true requires packaging_unit_name and packaging_unit_qty",
                        "packaging_unit_qty"));
            }
        }

        // has_expiry=true requires days > 0.
        Object hasExpiry = row.values.containsKey(ImportProductField.HAS_EXPIRY)
                ? row.values.get(ImportProductField.HAS_EXPIRY)
                : (creating ? Boolean.FALSE : row.existing.isHasExpiry());
        Object expiryDays = row.values.containsKey(ImportProductField.EXPIRES_AFTER_DAYS)
                ? row.values.get(ImportProductField.EXPIRES_AFTER_DAYS)
                : (creating ? null : row.existing.getExpiresAfterDays());
        if (Boolean.TRUE.equals(hasExpiry) && (expiryDays == null || (Integer) expiryDays <= 0)) {
            row.issues.add(new Issue("product.expiry_days_required", SEV_ERROR,
                    "has_expiry=true requires expires_after_days > 0", "expires_after_days"));
        }

        // Barcode conflict against other live rows.
        Object plannedBarcode = row.values.containsKey(ImportProductField.BARCODE)
                ? row.values.get(ImportProductField.BARCODE)
                : (creating ? null : row.existing.getBarcode());
        String plannedStatus = finalStatus(row, options);
        if (plannedBarcode instanceof String pb && !pb.isBlank()
                && !GlobalProductStatus.ARCHIVED.equals(plannedStatus)) {
            GlobalProduct holder = catalogs.liveByBarcode.get(pb);
            if (holder != null && (row.existing == null || !holder.getId().equals(row.existing.getId()))) {
                row.issues.add(new Issue("product.barcode_conflict", SEV_ERROR,
                        "Barcode " + pb + " already belongs to '" + holder.getName() + "'", "barcode"));
            }
        }

        if (blankToNull(raw.cell("image_url")) != null) {
            row.issues.add(new Issue("image.not_portable", SEV_WARNING,
                    "image_url is ignored by import — use the SA image upload or promote", "image_url"));
        }

        if (creating && row.barcode == null && sku == null
                && blankToNull(raw.cell("id")) == null && row.ref == null) {
            row.issues.add(new Issue("product.no_stable_key", SEV_WARNING,
                    "Row has no id, barcode, sku_template or ref — re-importing will duplicate it", null));
        }
        return row;
    }

    // ---------------------------------------------------------------- parents

    private void resolveParent(
            RawRow raw,
            RowPlan row,
            Map<String, RowPlan> planByRef,
            Map<Integer, RowPlan> planByLine,
            Map<String, RawRow> fileById,
            Map<String, RawRow> fileByBarcode,
            Map<String, RawRow> fileBySku,
            Map<String, RowPlan> planByMatchedId,
            Catalogs catalogs,
            ImportOptions options,
            Plan plan
    ) {
        if ("skip".equals(row.requestedAction)) {
            return;
        }
        String parentRef = blankToNull(raw.cell("parent_ref"));
        String parentId = blankToNull(raw.cell("parent_id"));
        String parentBarcode = blankToNull(raw.cell("parent_barcode"));
        String parentSku = blankToNull(raw.cell("parent_sku"));
        String parentName = blankToNull(raw.cell("parent_name"));
        boolean parentColumnsPresent = raw.present("parent_ref") || raw.present("parent_id")
                || raw.present("parent_barcode") || raw.present("parent_sku");
        boolean anyKey = parentRef != null || parentId != null || parentBarcode != null || parentSku != null;

        if (!anyKey) {
            // Explicit unlink: parent columns present, all blank, action=update, link exists (§4.3.5).
            if (parentColumnsPresent && "update".equals(row.requestedAction) && row.existing != null
                    && blankToNull(row.existing.getVariantOfGlobalProductId()) != null) {
                row.values.put(ImportProductField.VARIANT_OF, null);
                row.issues.add(new Issue("relation.variant_unlinked", SEV_WARNING,
                        "Parent link will be removed (explicit blank with action=update)", "parent_ref"));
            }
            return;
        }

        String childStatus = finalStatus(row, options);
        ParentPlan parent = new ParentPlan();

        // 1) In-file resolution (ref first, then the other keys against rows in this file).
        RowPlan target = null;
        if (parentRef != null) {
            parent.key = parentRef;
            target = planByRef.get(parentRef);
            if (target == null) {
                row.issues.add(new Issue("relation.parent_not_found", SEV_ERROR,
                        "parent_ref '" + parentRef + "' does not match any row in this file", "parent_ref"));
                return;
            }
        } else {
            RawRow targetRaw = null;
            if (parentId != null) {
                parent.key = parentId;
                targetRaw = fileById.get(parentId);
            }
            if (targetRaw == null && parentBarcode != null) {
                parent.key = parentBarcode;
                targetRaw = fileByBarcode.get(parentBarcode);
            }
            if (targetRaw == null && parentSku != null) {
                parent.key = parentSku;
                targetRaw = fileBySku.get(parentSku.toLowerCase(Locale.ROOT));
            }
            if (targetRaw != null) {
                target = planByLine.get(targetRaw.line);
            }
        }

        if (target != null) {
            if (target == row) {
                row.issues.add(new Issue("relation.parent_is_self", SEV_ERROR,
                        "A product cannot be its own parent", null));
                return;
            }
            // Depth / cycle: the in-file parent must not itself carry a parent key. Checked on
            // the raw keys (pass A) so the verdict is identical whatever the row order.
            if (target.hasParentKeys) {
                row.issues.add(new Issue("relation.parent_is_variant", SEV_ERROR,
                        "Parent '" + target.name + "' is itself a variant — nesting is limited to one level",
                        null));
                return;
            }
            if (target.hasError()) {
                row.action = "blocked";
                row.issues.add(new Issue("relation.parent_blocked", SEV_ERROR,
                        "Parent row (line " + target.line + ") has errors — fix it to import this child",
                        null));
                return;
            }
            String parentStatus = finalStatus(target, options);
            if (GlobalProductStatus.PUBLISHED.equals(childStatus)
                    && !GlobalProductStatus.PUBLISHED.equals(parentStatus)) {
                if (options.allowDraftParent()) {
                    row.issues.add(new Issue("relation.parent_not_published", SEV_WARNING,
                            "Parent '" + target.name + "' is " + parentStatus + "; child is published",
                            null));
                } else {
                    row.issues.add(new Issue("relation.parent_not_published", SEV_ERROR,
                            "Parent '" + target.name + "' is " + parentStatus
                                    + " but this child is published (publish the parent, or allow draft parents)",
                            null));
                    return;
                }
            }
            parent.resolution = "in_file";
            parent.plannedId = target.existing != null ? target.existing.getId() : target.plannedId;
            parent.state = target.existing != null ? "exists"
                    : (GlobalProductStatus.PUBLISHED.equals(parentStatus) ? "will_create" : "will_stay_draft");
            row.parent = parent;
            row.values.put(ImportProductField.VARIANT_OF, parent.plannedId);
            applyLinkedPackageStockDefault(row);
            return;
        }

        // 2) Catalog resolution.
        GlobalProduct existingParent = null;
        if (parentId != null) {
            existingParent = catalogs.byId.get(parentId);
        }
        if (existingParent == null && parentBarcode != null) {
            existingParent = catalogs.liveByBarcode.get(parentBarcode);
        }
        if (existingParent == null && parentSku != null) {
            List<GlobalProduct> bySku = catalogs.liveBySku
                    .getOrDefault(parentSku.toLowerCase(Locale.ROOT), List.of());
            if (bySku.size() > 1) {
                row.issues.add(new Issue("relation.parent_ambiguous", SEV_ERROR,
                        "parent_sku '" + parentSku + "' matches " + bySku.size() + " products", "parent_sku"));
                return;
            }
            existingParent = bySku.isEmpty() ? null : bySku.get(0);
        }

        if (existingParent != null) {
            // If that parent is also being updated by a row in this file, its planned status wins.
            RowPlan parentRow = planByMatchedId.get(existingParent.getId());
            String parentStatus = parentRow != null
                    ? finalStatus(parentRow, options)
                    : existingParent.getStatus();
            GlobalCatalogProductGraphWriter.ParentCheck check = graphWriter.checkParent(
                    existingParent.getCatalogId() == null ? "" : existingParent.getCatalogId(),
                    row.existing == null ? null : row.existing.getId(),
                    childStatus,
                    existingParent,
                    true); // status handled below with the planned status
            // Re-run the structural pieces against the child's catalog explicitly:
            if (row.existing != null && row.existing.getId().equals(existingParent.getId())) {
                row.issues.add(new Issue("relation.parent_is_self", SEV_ERROR,
                        "A product cannot be its own parent", null));
                return;
            }
            if (!check.ok() && !"relation.parent_not_published".equals(check.code())) {
                row.issues.add(new Issue(check.code(), SEV_ERROR, check.message(), null));
                return;
            }
            if (GlobalProductStatus.ARCHIVED.equals(parentStatus)) {
                row.issues.add(new Issue("relation.parent_not_published", SEV_ERROR,
                        "Parent '" + existingParent.getName() + "' is archived", null));
                return;
            }
            if (GlobalProductStatus.PUBLISHED.equals(childStatus)
                    && !GlobalProductStatus.PUBLISHED.equals(parentStatus)) {
                if (options.allowDraftParent()) {
                    row.issues.add(new Issue("relation.parent_not_published", SEV_WARNING,
                            "Parent '" + existingParent.getName() + "' is " + parentStatus
                                    + "; child is published", null));
                } else {
                    row.issues.add(new Issue("relation.parent_not_published", SEV_ERROR,
                            "Parent '" + existingParent.getName() + "' is " + parentStatus
                                    + " but this child is published (publish the parent, or allow draft parents)",
                            null));
                    return;
                }
            }
            parent.resolution = "existing";
            parent.plannedId = existingParent.getId();
            parent.state = GlobalProductStatus.PUBLISHED.equals(parentStatus) ? "published" : "will_stay_draft";
            row.parent = parent;
            row.values.put(ImportProductField.VARIANT_OF, existingParent.getId());
            applyLinkedPackageStockDefault(row);
            return;
        }

        // 3) Synthetic parent via parent_name (§5.2) — the "create missing parents" shape.
        if (parentName != null) {
            String dedupKey = parentName.toLowerCase(Locale.ROOT);
            SyntheticParent synthetic = plan.syntheticParents.get(dedupKey);
            if (synthetic == null) {
                synthetic = new SyntheticParent();
                synthetic.plannedId = UUID.randomUUID().toString();
                synthetic.name = parentName;
                synthetic.values.put(ImportProductField.NAME, parentName);
                String parentStatusCell = blankToNull(raw.cell("parent_status"));
                String syntheticStatus = parentStatusCell != null
                        ? safeStatus(parentStatusCell, row)
                        : childStatus;
                synthetic.values.put(ImportProductField.STATUS,
                        syntheticStatus == null ? options.defaultStatus() : syntheticStatus);
                String parentKit = blankToNull(raw.cell("parent_store_kit_id"));
                synthetic.values.put(ImportProductField.STORE_KIT_ID,
                        parentKit != null ? parentKit : options.defaultStoreKitId());
                Object childCategory = row.values.get(ImportProductField.CATEGORY_ID);
                if (childCategory == null && row.existing != null) {
                    childCategory = row.existing.getGlobalCategoryId();
                }
                if (childCategory != null) {
                    synthetic.values.put(ImportProductField.CATEGORY_ID, childCategory);
                }
                synthetic.values.put(ImportProductField.UNIT_TYPE, "each");
                synthetic.values.put(ImportProductField.ITEM_TYPE_KEY_HINT, "goods");
                synthetic.values.put(ImportProductField.SELLABLE, Boolean.TRUE);
                synthetic.values.put(ImportProductField.STOCKED, Boolean.TRUE);
                synthetic.values.put(ImportProductField.SORT_ORDER, 0);
                plan.syntheticParents.put(dedupKey, synthetic);
            }
            Object syntheticStatus = synthetic.values.get(ImportProductField.STATUS);
            if (GlobalProductStatus.PUBLISHED.equals(childStatus)
                    && !GlobalProductStatus.PUBLISHED.equals(syntheticStatus)
                    && !options.allowDraftParent()) {
                row.issues.add(new Issue("relation.parent_not_published", SEV_ERROR,
                        "New parent '" + parentName + "' would be " + syntheticStatus
                                + " but this child is published", null));
                return;
            }
            parent.key = parentName;
            parent.resolution = "synthetic";
            parent.plannedId = synthetic.plannedId;
            parent.state = "will_create";
            row.parent = parent;
            row.values.put(ImportProductField.VARIANT_OF, synthetic.plannedId);
            row.issues.add(new Issue("relation.parent_will_create", SEV_INFO,
                    "Parent '" + parentName + "' will be created", "parent_name"));
            applyLinkedPackageStockDefault(row);
            return;
        }

        row.issues.add(new Issue("relation.parent_not_found", SEV_ERROR,
                "Parent not found in this file or the catalog (add parent_name to create it)",
                parentSku != null ? "parent_sku" : parentBarcode != null ? "parent_barcode" : "parent_id"));
    }

    /** A linked package/selling unit shares the parent's stock: stocked defaults to false on create. */
    private void applyLinkedPackageStockDefault(RowPlan row) {
        if (row.existing == null && !row.stockedExplicit
                && Boolean.TRUE.equals(row.values.get(ImportProductField.PACKAGE_VARIANT))) {
            row.values.put(ImportProductField.STOCKED, Boolean.FALSE);
        }
    }

    private void classifyRow(RowPlan row, ImportOptions options) {
        if ("blocked".equals(row.action)) {
            return;
        }
        if ("skip".equals(row.requestedAction)) {
            row.action = "skip";
            return;
        }
        if (row.hasError()) {
            row.action = "error";
            return;
        }
        if (row.existing == null) {
            row.action = "create";
            row.diff.putAll(diffForCreate(row));
            return;
        }
        Map<String, List<String>> diff = diffForUpdate(row);
        if (diff.isEmpty() && !row.revived) {
            row.action = "unchanged";
            row.issues.add(new Issue("product.unchanged", SEV_INFO, "No changes for this row", null));
        } else {
            row.action = "update";
            row.diff.putAll(diff);
        }
    }

    // ---------------------------------------------------------------- packs

    private PackPlan planPackRow(
            RawRow raw,
            Catalogs catalogs,
            ImportOptions options,
            Map<String, List<Integer>> packCodeLines,
            Map<String, List<Integer>> refLines
    ) {
        PackPlan pack = new PackPlan();
        pack.line = raw.line;

        String ref = blankToNull(raw.cell("ref"));
        if (ref == null) {
            pack.issues.add(new Issue("file.ref_required", SEV_ERROR,
                    "pack rows need a ref so pack_item rows can point at them", "ref"));
        } else if (refLines.getOrDefault(ref, List.of()).size() > 1) {
            pack.issues.add(new Issue("file.duplicate_ref", SEV_ERROR,
                    "ref '" + ref + "' appears on lines " + refLines.get(ref), "ref"));
        } else {
            pack.ref = ref;
        }

        String code = blankToNull(raw.cell("pack_code"));
        if (code == null) {
            pack.issues.add(new Issue("pack.code_required", SEV_ERROR, "pack_code is required", "pack_code"));
        } else if (code.length() > 64) {
            pack.issues.add(new Issue("pack.invalid_code", SEV_ERROR,
                    "pack_code is longer than 64 characters", "pack_code"));
        } else if (packCodeLines.getOrDefault(code.toLowerCase(Locale.ROOT), List.of()).size() > 1) {
            pack.issues.add(new Issue("pack.duplicate_code_in_file", SEV_ERROR,
                    "pack_code '" + code + "' appears on lines "
                            + packCodeLines.get(code.toLowerCase(Locale.ROOT)), "pack_code"));
        } else {
            pack.code = code;
            pack.existing = catalogs.packsByCode.get(code.toLowerCase(Locale.ROOT));
        }

        String name = blankToNull(raw.cell("pack_name"));
        if (name != null && name.length() > 255) {
            pack.issues.add(new Issue("pack.invalid_name", SEV_ERROR,
                    "pack_name is longer than 255 characters", "pack_name"));
        }
        pack.name = name;
        if (pack.existing == null && name == null) {
            pack.issues.add(new Issue("pack.name_required", SEV_ERROR,
                    "pack_name is required for a new pack", "pack_name"));
        }

        pack.descriptionPresent = raw.present("pack_description");
        pack.description = blankToNull(raw.cell("pack_description"));

        pack.storeKitPresent = raw.present("pack_store_kit_id");
        String kit = blankToNull(raw.cell("pack_store_kit_id"));
        if (kit != null && !KNOWN_STORE_KITS.contains(kit)) {
            pack.issues.add(new Issue("product.unknown_store_kit", SEV_ERROR,
                    "Unknown store kit '" + kit + "' " + KNOWN_STORE_KITS, "pack_store_kit_id"));
        }
        pack.storeKitId = kit;
        if (!pack.storeKitPresent && pack.existing == null) {
            pack.storeKitPresent = true;
            pack.storeKitId = options.defaultStoreKitId();
        }

        String status = blankToNull(raw.cell("pack_status"));
        if (status != null) {
            String normalized = status.toLowerCase(Locale.ROOT);
            if (!"draft".equals(normalized) && !"published".equals(normalized)) {
                pack.issues.add(new Issue("pack.invalid_status", SEV_ERROR,
                        "pack_status must be draft or published", "pack_status"));
            } else {
                pack.status = normalized;
            }
        } else if (pack.existing == null) {
            pack.status = GlobalProductStatus.PUBLISHED.equals(options.defaultStatus())
                    ? "published" : "draft";
        }

        String sort = blankToNull(raw.cell("pack_sort_order"));
        if (sort != null) {
            try {
                pack.sortOrder = Integer.parseInt(sort);
            } catch (NumberFormatException ex) {
                pack.issues.add(new Issue("pack.invalid_sort_order", SEV_ERROR,
                        "Invalid integer '" + sort + "' for pack_sort_order", "pack_sort_order"));
            }
        }

        String replace = blankToNull(raw.cell("pack_replace"));
        if (replace != null) {
            String token = replace.toLowerCase(Locale.ROOT);
            if (TRUE_TOKENS.contains(token)) {
                pack.replace = true;
            } else if (!FALSE_TOKENS.contains(token)) {
                pack.issues.add(new Issue("pack.invalid_replace", SEV_ERROR,
                        "Unrecognised boolean '" + replace + "' for pack_replace", "pack_replace"));
            }
        }

        if (pack.hasError()) {
            pack.action = "error";
        } else if (pack.existing != null) {
            pack.action = "update";
        } else {
            pack.action = "create";
            pack.plannedId = UUID.randomUUID().toString();
        }
        return pack;
    }

    private void planPackItemRow(
            RawRow raw,
            Map<String, PackPlan> packByRef,
            Map<String, RowPlan> planByRef,
            Map<String, RawRow> fileByBarcode,
            Map<String, RawRow> fileBySku,
            Map<String, RawRow> fileById,
            Map<Integer, RowPlan> planByLine,
            Catalogs catalogs,
            Plan plan
    ) {
        String packRef = blankToNull(raw.cell("pack_ref"));
        PackPlan pack = packRef == null ? null : packByRef.get(packRef);
        if (pack == null && packRef != null) {
            // §5.4: pack_ref may also name an existing pack's code when no pack row is in the file.
            GlobalProductPack existing = catalogs.packsByCode.get(packRef.toLowerCase(Locale.ROOT));
            if (existing != null) {
                pack = new PackPlan();
                pack.line = raw.line;
                pack.ref = packRef;
                pack.code = existing.getCode();
                pack.existing = existing;
                pack.action = "update";
                plan.packs.add(pack);
                plan.packUpdate++;
                packByRef.put(packRef, pack);
            }
        }
        if (pack == null) {
            RowPlan orphan = new RowPlan();
            orphan.line = raw.line;
            orphan.rowType = "pack_item";
            orphan.action = "error";
            orphan.issues.add(new Issue("pack.ref_not_found", SEV_ERROR,
                    "pack_ref '" + packRef + "' matches no pack row in this file and no existing pack code",
                    "pack_ref"));
            plan.rows.add(orphan);
            return;
        }

        PackItemPlan item = new PackItemPlan();
        item.line = raw.line;
        item.packRef = packRef;

        String productRef = blankToNull(raw.cell("product_ref"));
        String productBarcode = blankToNull(raw.cell("product_barcode"));
        String productSku = blankToNull(raw.cell("product_sku"));
        String productId = blankToNull(raw.cell("product_id"));

        RowPlan targetRow = null;
        GlobalProduct targetExisting = null;
        if (productRef != null) {
            targetRow = planByRef.get(productRef);
            if (targetRow == null) {
                pack.issues.add(new Issue("pack.product_not_found", SEV_ERROR,
                        "Line " + raw.line + ": product_ref '" + productRef + "' matches no row in this file",
                        "product_ref"));
                return;
            }
        } else if (productBarcode != null) {
            RawRow fileRow = fileByBarcode.get(productBarcode);
            targetRow = fileRow == null ? null : planByLine.get(fileRow.line);
            if (targetRow == null) {
                targetExisting = catalogs.liveByBarcode.get(productBarcode);
            }
        } else if (productSku != null) {
            RawRow fileRow = fileBySku.get(productSku.toLowerCase(Locale.ROOT));
            targetRow = fileRow == null ? null : planByLine.get(fileRow.line);
            if (targetRow == null) {
                List<GlobalProduct> bySku = catalogs.liveBySku
                        .getOrDefault(productSku.toLowerCase(Locale.ROOT), List.of());
                if (bySku.size() > 1) {
                    pack.issues.add(new Issue("pack.product_ambiguous", SEV_ERROR,
                            "Line " + raw.line + ": product_sku '" + productSku + "' matches "
                                    + bySku.size() + " products", "product_sku"));
                    return;
                }
                targetExisting = bySku.isEmpty() ? null : bySku.get(0);
            }
        } else if (productId != null) {
            RawRow fileRow = fileById.get(productId);
            targetRow = fileRow == null ? null : planByLine.get(fileRow.line);
            if (targetRow == null) {
                targetExisting = catalogs.byId.get(productId);
            }
        } else {
            pack.issues.add(new Issue("pack.product_key_required", SEV_ERROR,
                    "Line " + raw.line + ": pack_item needs product_ref, product_barcode, product_sku or product_id",
                    "product_ref"));
            return;
        }

        String resolvedId;
        if (targetRow != null) {
            if (targetRow.hasError() || "blocked".equals(targetRow.action)) {
                pack.issues.add(new Issue("pack.member_blocked", SEV_WARNING,
                        "Line " + raw.line + ": member '" + targetRow.name
                                + "' skipped — its product row has errors", null));
                return;
            }
            resolvedId = targetRow.existing != null ? targetRow.existing.getId() : targetRow.plannedId;
        } else if (targetExisting != null) {
            if (GlobalProductStatus.ARCHIVED.equals(targetExisting.getStatus())) {
                pack.issues.add(new Issue("pack.member_archived", SEV_WARNING,
                        "Line " + raw.line + ": member '" + targetExisting.getName()
                                + "' is archived and was skipped", null));
                return;
            }
            resolvedId = targetExisting.getId();
        } else {
            pack.issues.add(new Issue("pack.product_not_found", SEV_ERROR,
                    "Line " + raw.line + ": pack member not found in this file or the catalog", null));
            return;
        }

        boolean duplicate = pack.items.stream().anyMatch(i -> resolvedId.equals(i.resolvedProductId));
        if (duplicate) {
            pack.issues.add(new Issue("pack.duplicate_member", SEV_WARNING,
                    "Line " + raw.line + ": duplicate pack member collapsed", null));
            return;
        }
        item.resolvedProductId = resolvedId;
        String sort = blankToNull(raw.cell("item_sort_order"));
        if (sort != null) {
            try {
                item.sortOrder = Integer.parseInt(sort);
            } catch (NumberFormatException ex) {
                pack.issues.add(new Issue("pack.invalid_item_sort", SEV_WARNING,
                        "Line " + raw.line + ": invalid item_sort_order ignored", "item_sort_order"));
            }
        }
        pack.items.add(item);
    }

    // ---------------------------------------------------------------- identity / version

    private void resolveIdentity(RowPlan row, RawRow raw, Catalogs catalogs, ImportOptions options) {
        String id = blankToNull(raw.cell("id"));
        String barcode = row.barcode;
        String sku = blankToNull(raw.cell("sku_template"));

        GlobalProduct byId = null;
        if (id != null) {
            byId = catalogs.byId.get(id);
            if (byId == null) {
                row.issues.add(new Issue("product.id_not_in_catalog", SEV_WARNING,
                        "id " + id + " is not in this catalog — matching falls through to barcode/sku", "id"));
            }
        }
        GlobalProduct byBarcode = barcode == null ? null : catalogs.liveByBarcode.get(barcode);
        List<GlobalProduct> bySku = sku == null
                ? List.of()
                : catalogs.liveBySku.getOrDefault(sku.toLowerCase(Locale.ROOT), List.of());

        Set<String> distinct = new LinkedHashSet<>();
        if (byId != null) {
            distinct.add(byId.getId());
        }
        if (byBarcode != null) {
            distinct.add(byBarcode.getId());
        }
        if (distinct.size() > 1) {
            row.issues.add(new Issue("product.identity_conflict", SEV_ERROR,
                    "id and barcode resolve to different products", null));
            return;
        }
        if (byId == null && byBarcode == null && !bySku.isEmpty()) {
            if (bySku.size() > 1) {
                row.issues.add(new Issue("product.sku_ambiguous", SEV_ERROR,
                        "sku_template matches " + bySku.size() + " products — it is not unique", "sku_template"));
                return;
            }
            row.existing = bySku.get(0);
            return;
        }
        if (byId != null) {
            row.existing = byId;
            return;
        }
        if (byBarcode != null) {
            row.existing = byBarcode;
            return;
        }
        if (barcode != null) {
            GlobalProduct archived = catalogs.archivedByBarcode.get(barcode);
            if (archived != null) {
                if (!options.reviveArchived()) {
                    row.issues.add(new Issue("product.archived_conflict", SEV_ERROR,
                            "An archived product already uses barcode " + barcode
                                    + " (enable revive_archived to restore it)", "barcode"));
                    return;
                }
                row.existing = archived;
                row.revived = true;
                row.issues.add(new Issue("product.revived_from_archive", SEV_INFO,
                        "Archived product '" + archived.getName() + "' will be revived", "barcode"));
            }
        }
    }

    private void checkVersion(RowPlan row, RawRow raw, ImportOptions options) {
        String rawVersion = blankToNull(raw.cell("version"));
        if (rawVersion == null || row.existing == null) {
            return;
        }
        long fileVersion;
        try {
            fileVersion = Long.parseLong(rawVersion);
        } catch (NumberFormatException ex) {
            row.issues.add(new Issue("product.invalid_version", SEV_ERROR,
                    "Invalid version: " + rawVersion, "version"));
            return;
        }
        if (fileVersion != row.existing.getVersion() && !options.force()) {
            row.issues.add(new Issue("product.stale_version", SEV_ERROR,
                    "Row was exported at version " + fileVersion + " but the product is now at version "
                            + row.existing.getVersion() + " (use force to override)", "version"));
        }
    }

    // ---------------------------------------------------------------- assignments

    private void assignText(RowPlan row, RawRow raw, String column, ImportProductField field, boolean clearable) {
        if (!raw.present(column)) {
            return;
        }
        String value = blankToNull(raw.cell(column));
        if (value == null) {
            if (clearable && CLEARABLE_TEXT.contains(column)) {
                row.values.put(field, null);
            }
            return;
        }
        row.values.put(field, value);
    }

    private void assignUnitType(RowPlan row, RawRow raw, boolean creating) {
        if (raw.present("unit_type")) {
            String value = blankToNull(raw.cell("unit_type"));
            if (value != null) {
                String normalized = value.toLowerCase(Locale.ROOT);
                if (!UNIT_TYPES.contains(normalized)) {
                    row.issues.add(new Issue("product.invalid_unit_type", SEV_ERROR,
                            "Unknown unit_type '" + value + "' (each|kg|g|l|ml|pack)", "unit_type"));
                    return;
                }
                row.values.put(ImportProductField.UNIT_TYPE, normalized);
                return;
            }
        }
        if (creating) {
            row.values.put(ImportProductField.UNIT_TYPE, "each");
        }
    }

    private String safeStatus(String value, RowPlan row) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!GlobalProductStatus.isAllowed(normalized)) {
            row.issues.add(new Issue("product.invalid_parent_status", SEV_ERROR,
                    "Unknown parent_status '" + value + "'", "parent_status"));
            return null;
        }
        return normalized;
    }

    private void assignStatus(RowPlan row, RawRow raw, boolean creating, ImportOptions options) {
        String value = raw.present("status") ? blankToNull(raw.cell("status")) : null;
        if (value != null) {
            String normalized = value.toLowerCase(Locale.ROOT);
            if (!GlobalProductStatus.isAllowed(normalized)) {
                row.issues.add(new Issue("product.invalid_status", SEV_ERROR,
                        "Unknown status '" + value + "' (draft|published|archived)", "status"));
                return;
            }
            row.values.put(ImportProductField.STATUS, normalized);
            return;
        }
        if (creating || row.revived) {
            row.values.put(ImportProductField.STATUS, options.defaultStatus());
        }
    }

    private void assignStoreKit(RowPlan row, RawRow raw, boolean creating, ImportOptions options) {
        if (raw.present("store_kit_id")) {
            String value = blankToNull(raw.cell("store_kit_id"));
            if (value == null) {
                // Explicit blank = unrestricted (visible to every shop type) — deliberate, warned.
                row.values.put(ImportProductField.STORE_KIT_ID, null);
                row.issues.add(new Issue("storekit.unrestricted", SEV_WARNING,
                        "Blank store_kit_id makes this product visible to every shop type", "store_kit_id"));
                return;
            }
            if (!KNOWN_STORE_KITS.contains(value)) {
                row.issues.add(new Issue("product.unknown_store_kit", SEV_ERROR,
                        "Unknown store kit '" + value + "' " + KNOWN_STORE_KITS, "store_kit_id"));
                return;
            }
            row.values.put(ImportProductField.STORE_KIT_ID, value);
            return;
        }
        // Absent column on create = run default (fixes finding 20: new rows no longer land NULL).
        if (creating) {
            row.values.put(ImportProductField.STORE_KIT_ID, options.defaultStoreKitId());
        }
    }

    private void assignBoolean(
            RowPlan row, RawRow raw, String column, ImportProductField field,
            boolean creating, boolean createDefault
    ) {
        if (raw.present(column)) {
            String value = blankToNull(raw.cell(column));
            if (value != null) {
                String token = value.toLowerCase(Locale.ROOT);
                if (TRUE_TOKENS.contains(token)) {
                    row.values.put(field, Boolean.TRUE);
                    return;
                }
                if (FALSE_TOKENS.contains(token)) {
                    row.values.put(field, Boolean.FALSE);
                    return;
                }
                row.issues.add(new Issue("product.invalid_" + column, SEV_ERROR,
                        "Unrecognised boolean '" + value + "' for " + column + " (true/false/1/0/yes/no)",
                        column));
                return;
            }
        }
        if (creating) {
            row.values.put(field, createDefault);
        }
    }

    private void assignDecimal(
            RowPlan row, RawRow raw, String column, ImportProductField field,
            BigDecimal min, BigDecimal max, int maxScale
    ) {
        if (!raw.present(column)) {
            return;
        }
        String value = blankToNull(raw.cell(column));
        if (value == null) {
            return;
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException ex) {
            row.issues.add(new Issue("product.invalid_" + column, SEV_ERROR,
                    "Invalid number '" + value + "' for " + column, column));
            return;
        }
        if (min != null && parsed.compareTo(min) < 0) {
            row.issues.add(new Issue("product.out_of_range", SEV_ERROR,
                    column + " must be ≥ " + min.toPlainString(), column));
            return;
        }
        if (max != null && parsed.compareTo(max) > 0) {
            row.issues.add(new Issue("product.out_of_range", SEV_ERROR,
                    column + " must be ≤ " + max.toPlainString(), column));
            return;
        }
        if (parsed.stripTrailingZeros().scale() > maxScale) {
            row.issues.add(new Issue("product.out_of_range", SEV_ERROR,
                    column + " allows at most " + maxScale + " decimal places", column));
            return;
        }
        row.values.put(field, parsed);
    }

    private void assignInteger(RowPlan row, RawRow raw, String column, ImportProductField field) {
        if (!raw.present(column)) {
            return;
        }
        String value = blankToNull(raw.cell(column));
        if (value == null) {
            return;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (field == ImportProductField.EXPIRES_AFTER_DAYS && parsed <= 0) {
                row.issues.add(new Issue("product.out_of_range", SEV_ERROR,
                        column + " must be > 0", column));
                return;
            }
            row.values.put(field, parsed);
        } catch (NumberFormatException ex) {
            row.issues.add(new Issue("product.invalid_" + column, SEV_ERROR,
                    "Invalid integer '" + value + "' for " + column, column));
        }
    }

    private void assignCategory(
            RowPlan row, RawRow raw, Catalogs catalogs, ImportOptions options, Plan plan, boolean creating
    ) {
        String categoryId = blankToNull(raw.cell("global_category_id"));
        String categoryName = blankToNull(raw.cell("category_name"));
        String categorySlug = blankToNull(raw.cell("category_slug"));
        boolean anyPresent = raw.present("global_category_id")
                || raw.present("category_name")
                || raw.present("category_slug");
        if (!anyPresent || (categoryId == null && categoryName == null && categorySlug == null)) {
            return;
        }

        Set<String> resolved = new LinkedHashSet<>();
        if (categoryId != null) {
            GlobalCategory byId = catalogs.categoriesById.get(categoryId);
            if (byId == null) {
                row.issues.add(new Issue("category.wrong_catalog", SEV_ERROR,
                        "global_category_id " + categoryId + " does not belong to this catalog",
                        "global_category_id"));
                return;
            }
            resolved.add(byId.getId());
        }
        if (categorySlug != null) {
            GlobalCategory bySlug = catalogs.categoriesBySlug.get(categorySlug.toLowerCase(Locale.ROOT));
            if (bySlug == null) {
                row.issues.add(new Issue("category.unknown", SEV_ERROR,
                        "Unknown category_slug '" + categorySlug + "'", "category_slug"));
                return;
            }
            resolved.add(bySlug.getId());
        }
        if (categoryName != null) {
            List<GlobalCategory> byName = catalogs.categoriesByLowerName
                    .getOrDefault(categoryName.toLowerCase(Locale.ROOT), List.of());
            if (byName.size() > 1) {
                row.issues.add(new Issue("category.ambiguous_name", SEV_ERROR,
                        "category_name '" + categoryName + "' matches " + byName.size() + " categories",
                        "category_name"));
                return;
            }
            if (byName.size() == 1) {
                resolved.add(byName.get(0).getId());
            } else {
                if (!options.createCategories()) {
                    row.issues.add(new Issue("category.unknown", SEV_ERROR,
                            "Unknown category_name '" + categoryName
                                    + "' (enable create_categories to create it)", "category_name"));
                    return;
                }
                PlannedCategory planned = plan.plannedCategories.computeIfAbsent(
                        categoryName.toLowerCase(Locale.ROOT),
                        k -> new PlannedCategory(UUID.randomUUID().toString(), categoryName));
                resolved.add(planned.id());
                row.issues.add(new Issue("category.will_create", SEV_INFO,
                        "Category '" + categoryName + "' will be created", "category_name"));
            }
        }
        if (resolved.size() > 1) {
            row.issues.add(new Issue("category.conflict", SEV_ERROR,
                    "global_category_id / category_name / category_slug resolve to different categories", null));
            return;
        }
        row.values.put(ImportProductField.CATEGORY_ID, resolved.iterator().next());
    }

    // ---------------------------------------------------------------- diff / status

    private Map<String, List<String>> diffForUpdate(RowPlan row) {
        Map<String, List<String>> diff = new LinkedHashMap<>();
        for (Map.Entry<ImportProductField, Object> entry : row.values.entrySet()) {
            if (!entry.getKey().matches(row.existing, entry.getValue())) {
                diff.put(fieldLabel(entry.getKey()), List.of(
                        render(entry.getKey().read(row.existing)),
                        render(entry.getValue())));
            }
        }
        return diff;
    }

    private Map<String, List<String>> diffForCreate(RowPlan row) {
        Map<String, List<String>> diff = new LinkedHashMap<>();
        for (Map.Entry<ImportProductField, Object> entry : row.values.entrySet()) {
            String rendered = render(entry.getValue());
            if (!rendered.isEmpty()) {
                diff.put(fieldLabel(entry.getKey()), List.of("", rendered));
            }
        }
        return diff;
    }

    private static String fieldLabel(ImportProductField field) {
        return field.name().toLowerCase(Locale.ROOT);
    }

    private static String render(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal d) {
            return d.toPlainString();
        }
        return String.valueOf(value);
    }

    private String finalStatus(RowPlan row, ImportOptions options) {
        Object planned = row.values.get(ImportProductField.STATUS);
        if (planned != null) {
            return (String) planned;
        }
        if (row.existing != null) {
            return row.existing.getStatus();
        }
        return options.defaultStatus();
    }

    private String normalizeAction(String rawValue, RowPlan row) {
        String value = blankToNull(rawValue);
        if (value == null) {
            return "upsert";
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!Set.of("upsert", "create", "update", "skip").contains(normalized)) {
            row.issues.add(new Issue("product.invalid_action", SEV_ERROR,
                    "Unknown action '" + rawValue + "' (upsert|create|update|skip)", "action"));
            return "upsert";
        }
        return normalized;
    }

    private static String normalizeRowType(String raw) {
        String value = raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
        return value == null || value.isEmpty() ? "product" : value;
    }

    // ---------------------------------------------------------------- mode + headers

    private void detectMode(ParsedFile parsed, Plan plan) {
        Set<String> canonical = parsed.allCanonicalHeaders;
        if (canonical.contains("row_type") || parsed.multiSheet) {
            plan.mode = MODE_MANIFEST;
            return;
        }
        // Pack-ish columns without row_type fail the file (§5.1) — checked BEFORE the
        // parents shortcut, so a mixed file never imports its pack rows as products.
        for (String header : canonical) {
            if (ROW_TYPE_REQUIRED_HEADERS.contains(header)) {
                plan.fileIssues.add(new Issue("file.row_type_required", SEV_ERROR,
                        "Column '" + header + "' needs a row_type column — add row_type "
                                + "(product|pack|pack_item) so pack rows cannot be imported as products",
                        header));
                return;
            }
        }
        for (String header : canonical) {
            if (PARENT_KEY_HEADERS.contains(header)) {
                plan.mode = MODE_PRODUCTS_PARENTS;
                return;
            }
        }
        plan.mode = MODE_PRODUCTS;
    }

    /** Maps one sheet's raw headers to canonical names, reporting into the plan. */
    private Map<String, String> classifyHeaders(List<String> headers, Plan plan, boolean manifestSheet) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String header : headers) {
            if (header.isEmpty()) {
                continue;
            }
            if (KNOWN_HEADERS.contains(header)) {
                mapping.put(header, header);
                if (!plan.mappedColumns.contains(header)) {
                    plan.mappedColumns.add(header);
                }
                continue;
            }
            String alias = HEADER_ALIASES.get(header);
            if (alias != null) {
                mapping.put(header, alias);
                String label = header + " → " + alias;
                if (!plan.aliasedColumns.contains(label)) {
                    plan.aliasedColumns.add(label);
                    plan.fileIssues.add(new Issue("file.header_alias_used", SEV_WARNING,
                            "Column '" + header + "' was read as '" + alias + "'", header));
                }
                continue;
            }
            if (EXPLICIT_IGNORES.contains(header)) {
                if (!plan.ignoredColumns.contains(header)) {
                    plan.ignoredColumns.add(header);
                    plan.fileIssues.add(new Issue("file.column_ignored", SEV_INFO,
                            "Column '" + header + "' is ignored by global import", header));
                }
                continue;
            }
            if (!plan.unknownColumns.contains(header)) {
                plan.unknownColumns.add(header);
                plan.fileIssues.add(new Issue("file.unknown_header",
                        manifestSheet ? SEV_ERROR : SEV_WARNING,
                        manifestSheet
                                ? "Unknown column '" + header + "' — manifest files use strict headers"
                                : "Unknown column '" + header + "' is ignored",
                        header));
            }
        }
        return mapping;
    }

    // ---------------------------------------------------------------- parsing (CSV + xlsx)

    /** One parsed data row: canonical-keyed cells + which canonical columns its sheet carries. */
    record RawRow(int line, Map<String, String> cells, Set<String> presentColumns, boolean impliedPackageVariant) {

        String cell(String canonical) {
            return cells.getOrDefault(canonical, "");
        }

        boolean present(String canonical) {
            return presentColumns.contains(canonical);
        }
    }

    record ParsedFile(List<RawRow> rows, Set<String> allCanonicalHeaders, boolean multiSheet) {
    }

    private ParsedFile parseFile(byte[] data, Plan plan) throws IOException {
        if (data.length > 4 && data[0] == 0x50 && data[1] == 0x4B) {
            return parseWorkbook(data, plan);
        }
        return parseCsv(data, plan);
    }

    private ParsedFile parseCsv(byte[] data, Plan plan) throws IOException {
        String text = decode(data);
        char delimiter = sniffDelimiter(text);
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build();
        try (Reader reader = new StringReader(text);
                CSVParser parser = format.parse(reader)) {
            List<String> rawHeaders = parser.getHeaderNames().stream()
                    .map(SuperAdminGlobalCatalogImportService::normalizeHeader)
                    .toList();
            boolean manifestSheet = rawHeaders.contains("row_type");
            Map<String, String> mapping = classifyHeaders(rawHeaders, plan, manifestSheet);
            Set<String> present = Set.copyOf(mapping.values());
            String impliedHeader = mapping.containsKey("pack_of_sku") ? "pack_of_sku" : null;

            List<RawRow> rows = new ArrayList<>();
            for (CSVRecord rec : parser) {
                Map<String, String> cells = new LinkedHashMap<>();
                boolean implied = false;
                int i = 0;
                for (String rawHeader : parser.getHeaderNames()) {
                    String normalized = rawHeaders.get(i++);
                    String canonical = mapping.get(normalized);
                    if (canonical == null) {
                        continue;
                    }
                    String cell = rec.isMapped(rawHeader) && rec.isSet(rawHeader) ? rec.get(rawHeader) : "";
                    cell = cell == null ? "" : cell.replace("﻿", "").trim();
                    if (!cell.isEmpty() || !cells.containsKey(canonical)) {
                        cells.put(canonical, cell);
                    }
                    if (normalized.equals(impliedHeader) && !cell.isEmpty()) {
                        implied = true;
                    }
                }
                rows.add(new RawRow((int) rec.getRecordNumber() + 1, cells, present, implied));
            }
            return new ParsedFile(rows, present, false);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not parse CSV header: " + ex.getMessage());
        }
    }

    /**
     * xlsx input (scope v1.1 U5). Either one sheet with the union header, or a workbook with
     * sheets named {@code products} / {@code packs} / {@code pack_items}: each sheet gets its
     * own header, {@code row_type} is implied per sheet, and typed cells survive — no more
     * leading-zero barcode truncation.
     */
    private ParsedFile parseWorkbook(byte[] data, Plan plan) throws IOException {
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            Map<String, String> typedSheets = new LinkedHashMap<>();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                String sheetName = workbook.getSheetName(i).trim().toLowerCase(Locale.ROOT);
                switch (sheetName) {
                    case "products" -> typedSheets.put(workbook.getSheetName(i), "product");
                    case "packs" -> typedSheets.put(workbook.getSheetName(i), "pack");
                    case "pack_items", "pack items" -> typedSheets.put(workbook.getSheetName(i), "pack_item");
                    default -> {
                    }
                }
            }
            boolean multiSheet = !typedSheets.isEmpty();
            List<RawRow> rows = new ArrayList<>();
            Set<String> allHeaders = new LinkedHashSet<>();
            int lineCounter = 1;

            List<Map.Entry<String, String>> sheetsToRead = multiSheet
                    ? typedSheets.entrySet().stream().toList()
                    : List.of(Map.entry(workbook.getSheetName(0), (String) null));

            for (Map.Entry<String, String> entry : sheetsToRead) {
                Sheet sheet = workbook.getSheet(entry.getKey());
                String impliedRowType = entry.getValue();
                if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
                    continue;
                }
                Row headerRow = sheet.getRow(sheet.getFirstRowNum());
                if (headerRow == null) {
                    continue;
                }
                List<String> rawHeaders = new ArrayList<>();
                for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                    Cell cell = headerRow.getCell(c);
                    rawHeaders.add(normalizeHeader(cell == null ? "" : formatter.formatCellValue(cell)));
                }
                boolean manifestSheet = multiSheet || rawHeaders.contains("row_type");
                Map<String, String> mapping = classifyHeaders(rawHeaders, plan, manifestSheet);
                Set<String> present = new LinkedHashSet<>(mapping.values());
                if (impliedRowType != null) {
                    present.add("row_type");
                }
                allHeaders.addAll(present);
                String impliedHeader = mapping.containsKey("pack_of_sku") ? "pack_of_sku" : null;

                for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                    Row dataRow = sheet.getRow(r);
                    if (dataRow == null) {
                        continue;
                    }
                    Map<String, String> cells = new LinkedHashMap<>();
                    boolean implied = false;
                    boolean anyValue = false;
                    for (int c = 0; c < rawHeaders.size(); c++) {
                        String canonical = mapping.get(rawHeaders.get(c));
                        if (canonical == null) {
                            continue;
                        }
                        String value = cellText(dataRow.getCell(c), formatter);
                        if (!value.isEmpty()) {
                            anyValue = true;
                        }
                        if (!value.isEmpty() || !cells.containsKey(canonical)) {
                            cells.put(canonical, value);
                        }
                        if (rawHeaders.get(c).equals(impliedHeader) && !value.isEmpty()) {
                            implied = true;
                        }
                    }
                    if (!anyValue) {
                        continue;
                    }
                    if (impliedRowType != null && blankToNull(cells.get("row_type")) == null) {
                        cells.put("row_type", impliedRowType);
                    }
                    lineCounter++;
                    rows.add(new RawRow(lineCounter, cells, Set.copyOf(present), implied));
                }
            }
            if (multiSheet) {
                allHeaders.add("row_type");
            }
            return new ParsedFile(rows, allHeaders, multiSheet);
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not read the Excel file: " + ex.getMessage());
        }
    }

    /** Renders a cell preserving typed values (a numeric barcode never turns scientific). */
    private static String cellText(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType()
                : cell.getCellType();
        if (type == CellType.NUMERIC && !org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
            BigDecimal value = BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros();
            return value.scale() <= 0 ? value.toBigInteger().toString() : value.toPlainString();
        }
        return formatter.formatCellValue(cell).replace("﻿", "").trim();
    }

    private static String decode(byte[] data) {
        byte[] body = data;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            body = new byte[data.length - 3];
            System.arraycopy(data, 3, body, 0, body.length);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException ex) {
            return new String(body, Charset.forName("windows-1252"));
        }
    }

    private static char sniffDelimiter(String text) {
        int end = text.indexOf('\n');
        String firstLine = end < 0 ? text : text.substring(0, end);
        long commas = count(firstLine, ',');
        long semis = count(firstLine, ';');
        long tabs = count(firstLine, '\t');
        if (semis > commas && semis >= tabs) {
            return ';';
        }
        if (tabs > commas && tabs > semis) {
            return '\t';
        }
        return ',';
    }

    private static long count(String s, char c) {
        boolean inQuotes = false;
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
            } else if (ch == c && !inQuotes) {
                n++;
            }
        }
        return n;
    }

    private static String normalizeHeader(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("﻿", "").trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    // ---------------------------------------------------------------- state loading

    private record Catalogs(
            Map<String, GlobalProduct> byId,
            Map<String, GlobalProduct> liveByBarcode,
            Map<String, List<GlobalProduct>> liveBySku,
            Map<String, GlobalProduct> archivedByBarcode,
            Map<String, GlobalCategory> categoriesById,
            Map<String, List<GlobalCategory>> categoriesByLowerName,
            Map<String, GlobalCategory> categoriesBySlug,
            Map<String, GlobalProductPack> packsByCode
    ) {
    }

    private Catalogs loadCatalogState(String catalogId) {
        List<GlobalProduct> products = globalProductRepository.findByCatalogId(catalogId);
        Map<String, GlobalProduct> byId = new HashMap<>();
        Map<String, GlobalProduct> liveByBarcode = new HashMap<>();
        Map<String, List<GlobalProduct>> liveBySku = new HashMap<>();
        Map<String, GlobalProduct> archivedByBarcode = new HashMap<>();
        for (GlobalProduct p : products) {
            byId.put(p.getId(), p);
            boolean archived = GlobalProductStatus.ARCHIVED.equals(p.getStatus());
            String barcode = blankToNull(p.getBarcode());
            if (barcode != null) {
                if (archived) {
                    archivedByBarcode.putIfAbsent(barcode, p);
                } else {
                    liveByBarcode.putIfAbsent(barcode, p);
                }
            }
            String sku = blankToNull(p.getSkuTemplate());
            if (sku != null && !archived) {
                liveBySku.computeIfAbsent(sku.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(p);
            }
        }
        Map<String, GlobalCategory> categoriesById = new HashMap<>();
        Map<String, List<GlobalCategory>> categoriesByLowerName = new HashMap<>();
        Map<String, GlobalCategory> categoriesBySlug = new HashMap<>();
        for (GlobalCategory c : globalCategoryRepository.findByCatalogIdOrderByPositionAsc(catalogId)) {
            categoriesById.put(c.getId(), c);
            if (c.isActive()) {
                categoriesByLowerName
                        .computeIfAbsent(c.getName().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                        .add(c);
                categoriesBySlug.putIfAbsent(c.getSlug().toLowerCase(Locale.ROOT), c);
            }
        }
        Map<String, GlobalProductPack> packsByCode = new HashMap<>();
        for (GlobalProductPack pack : globalProductPackRepository.findByCatalogIdOrderBySortOrderAsc(catalogId)) {
            packsByCode.putIfAbsent(pack.getCode().toLowerCase(Locale.ROOT), pack);
        }
        return new Catalogs(byId, liveByBarcode, liveBySku, archivedByBarcode,
                categoriesById, categoriesByLowerName, categoriesBySlug, packsByCode);
    }

    private void createPlannedCategories(String catalogId, Plan plan) {
        if (plan.plannedCategories.isEmpty()) {
            return;
        }
        Set<String> takenSlugs = new HashSet<>();
        globalCategoryRepository.findByCatalogIdOrderByPositionAsc(catalogId)
                .forEach(c -> takenSlugs.add(c.getSlug().toLowerCase(Locale.ROOT)));
        for (PlannedCategory planned : plan.plannedCategories.values()) {
            GlobalCategory category = new GlobalCategory();
            category.setId(planned.id());
            category.setCatalogId(catalogId);
            category.setName(planned.name());
            String slug = slugify(planned.name());
            String candidate = slug;
            int suffix = 2;
            while (takenSlugs.contains(candidate.toLowerCase(Locale.ROOT))) {
                candidate = slug + "-" + suffix++;
            }
            takenSlugs.add(candidate.toLowerCase(Locale.ROOT));
            category.setSlug(candidate);
            category.setActive(true);
            globalCategoryRepository.save(category);
        }
    }

    private static String slugify(String name) {
        String slug = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        slug = slug.replaceAll("^-+|-+$", "");
        return slug.isBlank() ? "category" : slug.substring(0, Math.min(slug.length(), 191));
    }

    // ---------------------------------------------------------------- responses

    private ImportPreviewResponse toPreviewResponse(Plan plan) {
        int create = 0;
        int update = 0;
        int unchanged = 0;
        int skipped = 0;
        int errors = 0;
        int blocked = 0;
        List<ImportRowPlanResponse> rows = new ArrayList<>(plan.rows.size());
        for (RowPlan row : plan.rows) {
            switch (row.action) {
                case "create" -> create++;
                case "update" -> update++;
                case "unchanged" -> unchanged++;
                case "skip" -> skipped++;
                case "error" -> errors++;
                case "blocked" -> blocked++;
                default -> {
                }
            }
            rows.add(new ImportRowPlanResponse(
                    row.line,
                    row.rowType,
                    row.ref,
                    row.action,
                    row.name,
                    row.barcode,
                    row.existing == null ? null : row.existing.getId(),
                    row.revived,
                    row.parent == null ? null : new ImportParentPlanResponse(
                            row.parent.key, row.parent.resolution, row.parent.plannedId, row.parent.state),
                    row.diff,
                    row.issues.stream().map(this::toIssueResponse).toList()));
        }
        List<ImportPackPlanResponse> packs = plan.packs.stream()
                .map(pack -> new ImportPackPlanResponse(
                        pack.line,
                        pack.ref,
                        pack.code,
                        pack.name != null ? pack.name
                                : pack.existing != null ? pack.existing.getName() : null,
                        pack.action,
                        pack.replace,
                        pack.items.size(),
                        pack.issues.stream().map(this::toIssueResponse).toList()))
                .toList();
        ImportSummaryResponse summary = new ImportSummaryResponse(
                create, update, unchanged, skipped, errors, (int) countWarnings(plan),
                plan.willPublish, plan.willStayDraft,
                blocked, plan.parentLinks, plan.packMemberships, plan.syntheticParents.size(),
                plan.packCreate, plan.packUpdate);
        return new ImportPreviewResponse(
                plan.fileName,
                plan.mode,
                plan.rowTypeCounts,
                new ImportColumnsResponse(
                        List.copyOf(plan.mappedColumns),
                        List.copyOf(plan.aliasedColumns),
                        List.copyOf(plan.ignoredColumns),
                        List.copyOf(plan.unknownColumns)),
                summary,
                rows,
                packs,
                plan.fileIssues.stream().map(this::toIssueResponse).toList());
    }

    private long countWarnings(Plan plan) {
        long rowWarnings = plan.rows.stream()
                .flatMap(r -> r.issues.stream())
                .filter(i -> SEV_WARNING.equals(i.severity()))
                .count();
        long packWarnings = plan.packs.stream()
                .flatMap(p -> p.issues.stream())
                .filter(i -> SEV_WARNING.equals(i.severity()))
                .count();
        long fileWarnings = plan.fileIssues.stream()
                .filter(i -> SEV_WARNING.equals(i.severity()))
                .count();
        return rowWarnings + packWarnings + fileWarnings;
    }

    private ImportRowResultResponse rowResult(RowPlan row, String action, String productId) {
        return new ImportRowResultResponse(
                row.line, row.rowType, action, productId, row.name,
                row.issues.stream().map(this::toIssueResponse).toList());
    }

    private ImportPackResultResponse packResult(
            PackPlan pack, String action, String packId, int added, int removed
    ) {
        return new ImportPackResultResponse(
                pack.line, pack.code, packId, action, added, removed,
                pack.issues.stream().map(this::toIssueResponse).toList());
    }

    private ImportIssueResponse toIssueResponse(Issue issue) {
        return new ImportIssueResponse(issue.code(), issue.severity(), issue.message(), issue.field());
    }

    // ---------------------------------------------------------------- audit / misc

    private void publishAudit(GlobalCatalog catalog, Plan plan, ImportSummaryResponse summary) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("fileName", plan.fileName);
            metadata.put("mode", plan.mode);
            metadata.put("rowCount", plan.rows.size());
            metadata.put("createdCount", summary.create());
            metadata.put("updatedCount", summary.update());
            metadata.put("unchangedCount", summary.unchanged());
            metadata.put("skippedCount", summary.skipped());
            metadata.put("errorCount", summary.errors());
            metadata.put("blockedCount", summary.blocked());
            metadata.put("parentLinkCount", summary.parentLinks());
            metadata.put("packMembershipCount", summary.packMemberships());
            metadata.put("syntheticParentCount", summary.syntheticParents());
            metadata.put("categoryCreatedCount", plan.plannedCategories.size());
            auditEventPublisher.publishSynchronous(auditEventBuilder
                    .builder(AuditEventCategory.PRODUCTS, AuditEventTypes.GLOBAL_PRODUCT_IMPORTED,
                            AuditEventSeverity.INFO)
                    .businessId("platform")
                    .actor(currentSuperAdminId(), AuditEventActorType.USER)
                    .target("global_catalog", catalog.getId())
                    .source("super_admin_portal")
                    .metadata(metadata)
                    .build());
        } catch (Exception ex) {
            log.warn("Failed to audit global catalog import: {}", ex.toString());
        }
    }

    private GlobalCatalog requireCatalog(String catalogId) {
        if (catalogId == null || catalogId.isBlank()) {
            return globalCatalogRepository.findByCode(DEFAULT_CATALOG_CODE)
                    .or(() -> globalCatalogRepository.findFirstByStatusOrderByVersionDesc(GlobalProductStatus.PUBLISHED))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No global catalog available"));
        }
        String key = catalogId.trim();
        return globalCatalogRepository.findById(key)
                .or(() -> globalCatalogRepository.findByCode(key))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Global catalog not found"));
    }

    private static String currentSuperAdminId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof String id) || id.isBlank()) {
            return null;
        }
        return id;
    }

    private static String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return Objects.requireNonNullElse(cursor.getMessage(), cursor.getClass().getSimpleName());
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
