package zelisline.ub.globalcatalog.application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.CsvImportResponse;
import zelisline.ub.globalcatalog.domain.GlobalCatalog;
import zelisline.ub.globalcatalog.domain.GlobalCategory;
import zelisline.ub.globalcatalog.domain.GlobalProduct;
import zelisline.ub.globalcatalog.domain.GlobalProductStatus;
import zelisline.ub.globalcatalog.repository.GlobalCatalogRepository;
import zelisline.ub.globalcatalog.repository.GlobalCategoryRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductRepository;
import zelisline.ub.integrations.csvimport.support.CsvImportReader;
import zelisline.ub.integrations.csvimport.support.CsvImportReader.SourceRow;

/**
 * Super-admin CSV export/import for global catalog products.
 *
 * <p>Import never touches {@code image_url}/{@code image_public_id} — images stay on the
 * upload/promote paths so shared Cloudinary assets are not orphaned or mis-owned.
 */
@Service
@RequiredArgsConstructor
public class SuperAdminGlobalCatalogCsvService {

    private static final String DEFAULT_CATALOG_CODE = "default";
    private static final int MAX_IMPORT_ROWS = 5_000;

    private static final String[] EXPORT_HEADERS = {
            "id",
            "name",
            "brand",
            "size",
            "barcode",
            "sku_template",
            "unit_type",
            "weighed",
            "sellable",
            "stocked",
            "status",
            "global_category_id",
            "category_slug",
            "recommended_buying_price",
            "recommended_selling_price",
            "suggested_margin_pct",
            "default_reorder_level",
            "default_reorder_qty",
            "default_min_stock_level",
            "has_expiry",
            "expires_after_days",
            "item_type_key_hint",
            "sort_order",
            "description",
            "image_url"
    };

    private final GlobalCatalogRepository globalCatalogRepository;
    private final GlobalCategoryRepository globalCategoryRepository;
    private final GlobalProductRepository globalProductRepository;

    @Transactional(readOnly = true)
    public byte[] exportCsv(String catalogId, String status, boolean missingImage) {
        GlobalCatalog catalog = requireCatalog(catalogId);
        String statusFilter = blankToNull(status);
        if (statusFilter != null && !GlobalProductStatus.isAllowed(statusFilter)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid status filter");
        }

        List<GlobalProduct> products = globalProductRepository.findAll().stream()
                .filter(p -> catalog.getId().equals(p.getCatalogId()))
                .filter(p -> statusFilter == null || statusFilter.equals(p.getStatus()))
                .filter(p -> !missingImage || blankToNull(p.getImageUrl()) == null)
                .sorted((a, b) -> {
                    int bySort = Integer.compare(a.getSortOrder(), b.getSortOrder());
                    if (bySort != 0) {
                        return bySort;
                    }
                    return a.getName().compareToIgnoreCase(b.getName());
                })
                .toList();

        Map<String, String> categorySlugById = globalCategoryRepository
                .findByCatalogIdAndActiveTrueOrderByPositionAsc(catalog.getId())
                .stream()
                .collect(java.util.stream.Collectors.toMap(GlobalCategory::getId, GlobalCategory::getSlug, (a, b) -> a));

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            out.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
                    CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder()
                            .setHeader(EXPORT_HEADERS)
                            .build())) {
                for (GlobalProduct product : products) {
                    printer.printRecord(
                            product.getId(),
                            product.getName(),
                            nullToEmpty(product.getBrand()),
                            nullToEmpty(product.getSize()),
                            nullToEmpty(product.getBarcode()),
                            nullToEmpty(product.getSkuTemplate()),
                            product.getUnitType(),
                            product.isWeighed(),
                            product.isSellable(),
                            product.isStocked(),
                            product.getStatus(),
                            nullToEmpty(product.getGlobalCategoryId()),
                            nullToEmpty(categorySlugById.get(product.getGlobalCategoryId())),
                            decimal(product.getRecommendedBuyingPrice()),
                            decimal(product.getRecommendedSellingPrice()),
                            decimal(product.getSuggestedMarginPct()),
                            decimal(product.getDefaultReorderLevel()),
                            decimal(product.getDefaultReorderQty()),
                            decimal(product.getDefaultMinStockLevel()),
                            product.isHasExpiry(),
                            product.getExpiresAfterDays() == null ? "" : product.getExpiresAfterDays(),
                            nullToEmpty(product.getItemTypeKeyHint()),
                            product.getSortOrder(),
                            nullToEmpty(product.getDescription()),
                            nullToEmpty(product.getImageUrl()));
                }
                printer.flush();
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not build CSV export");
        }
    }

    @Transactional
    public CsvImportResponse importCsv(String catalogId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty CSV file");
        }
        List<SourceRow> rows;
        try {
            rows = CsvImportReader.readRows(file.getInputStream());
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not parse CSV: " + ex.getMessage());
        }
        if (rows.size() > MAX_IMPORT_ROWS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "CSV exceeds " + MAX_IMPORT_ROWS + " data rows");
        }

        GlobalCatalog catalog = requireCatalog(catalogId);
        Map<String, String> categoryIdBySlug = globalCategoryRepository
                .findByCatalogIdAndActiveTrueOrderByPositionAsc(catalog.getId())
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        c -> c.getSlug().toLowerCase(Locale.ROOT),
                        GlobalCategory::getId,
                        (a, b) -> a));

        int created = 0;
        int updated = 0;
        int skipped = 0;
        List<String> warnings = new ArrayList<>();

        for (SourceRow row : rows) {
            try {
                ImportAction action = upsertRow(catalog.getId(), row, categoryIdBySlug, warnings);
                if (action == ImportAction.CREATED) {
                    created++;
                } else if (action == ImportAction.UPDATED) {
                    updated++;
                } else {
                    skipped++;
                }
            } catch (ResponseStatusException ex) {
                skipped++;
                warnings.add("Line " + row.lineNumber() + ": " + ex.getReason());
            } catch (RuntimeException ex) {
                skipped++;
                warnings.add("Line " + row.lineNumber() + ": " + ex.getMessage());
            }
        }
        return new CsvImportResponse(created, updated, skipped, warnings);
    }

    private ImportAction upsertRow(
            String catalogId,
            SourceRow row,
            Map<String, String> categoryIdBySlug,
            List<String> warnings
    ) {
        Map<String, String> cols = row.columns();
        String name = blankToNull(cols.get("name"));
        if (name == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }

        GlobalProduct existing = resolveExisting(catalogId, cols);
        boolean creating = existing == null;
        GlobalProduct product = creating ? new GlobalProduct() : existing;
        if (creating) {
            product.setCatalogId(catalogId);
        }

        String barcode = blankToNull(cols.get("barcode"));
        String status = cols.containsKey("status") && blankToNull(cols.get("status")) != null
                ? GlobalProductStatus.normalize(cols.get("status"))
                : (creating ? GlobalProductStatus.DRAFT : product.getStatus());

        assertBarcodeAvailable(catalogId, barcode, status, creating ? null : product.getId());

        product.setName(name);
        product.setBrand(blankToNull(cols.get("brand")));
        product.setSize(blankToNull(cols.get("size")));
        product.setBarcode(barcode);
        product.setSkuTemplate(blankToNull(cols.get("sku_template")));
        product.setUnitType(blankToNull(cols.get("unit_type")) != null
                ? cols.get("unit_type").trim()
                : (creating ? "each" : product.getUnitType()));
        product.setWeighed(parseBoolean(cols.get("weighed"), product.isWeighed()));
        product.setSellable(parseBoolean(cols.get("sellable"), creating || product.isSellable()));
        product.setStocked(parseBoolean(cols.get("stocked"), creating || product.isStocked()));
        product.setStatus(status);
        product.setGlobalCategoryId(resolveCategoryId(cols, categoryIdBySlug, creating ? null : product.getGlobalCategoryId()));
        product.setRecommendedBuyingPrice(parseDecimal(cols.get("recommended_buying_price"), product.getRecommendedBuyingPrice()));
        product.setRecommendedSellingPrice(parseDecimal(cols.get("recommended_selling_price"), product.getRecommendedSellingPrice()));
        product.setSuggestedMarginPct(parseDecimal(cols.get("suggested_margin_pct"), product.getSuggestedMarginPct()));
        product.setDefaultReorderLevel(parseDecimal(cols.get("default_reorder_level"), product.getDefaultReorderLevel()));
        product.setDefaultReorderQty(parseDecimal(cols.get("default_reorder_qty"), product.getDefaultReorderQty()));
        product.setDefaultMinStockLevel(parseDecimal(cols.get("default_min_stock_level"), product.getDefaultMinStockLevel()));
        product.setHasExpiry(parseBoolean(cols.get("has_expiry"), product.isHasExpiry()));
        product.setExpiresAfterDays(parseInteger(cols.get("expires_after_days"), product.getExpiresAfterDays()));
        product.setItemTypeKeyHint(blankToNull(cols.get("item_type_key_hint")) != null
                ? cols.get("item_type_key_hint").trim()
                : (creating ? "goods" : product.getItemTypeKeyHint()));
        Integer sortOrder = parseInteger(cols.get("sort_order"), creating ? 0 : product.getSortOrder());
        product.setSortOrder(sortOrder == null ? 0 : sortOrder);
        if (cols.containsKey("description")) {
            product.setDescription(blankToNull(cols.get("description")));
        }
        if (blankToNull(cols.get("image_url")) != null) {
            warnings.add("Line " + row.lineNumber() + ": image_url ignored (use SA image upload / promote)");
        }

        globalProductRepository.save(product);
        return creating ? ImportAction.CREATED : ImportAction.UPDATED;
    }

    private GlobalProduct resolveExisting(String catalogId, Map<String, String> cols) {
        String id = blankToNull(cols.get("id"));
        if (id != null) {
            return globalProductRepository.findById(id)
                    .filter(p -> catalogId.equals(p.getCatalogId()))
                    .orElse(null);
        }
        String barcode = blankToNull(cols.get("barcode"));
        if (barcode == null) {
            return null;
        }
        return globalProductRepository
                .findFirstByCatalogIdAndBarcodeAndStatusNotOrderByCreatedAtAsc(
                        catalogId, barcode, GlobalProductStatus.ARCHIVED)
                .orElse(null);
    }

    private String resolveCategoryId(
            Map<String, String> cols,
            Map<String, String> categoryIdBySlug,
            String fallback
    ) {
        String categoryId = blankToNull(cols.get("global_category_id"));
        if (categoryId != null) {
            return categoryId;
        }
        String slug = blankToNull(cols.get("category_slug"));
        if (slug != null) {
            String resolved = categoryIdBySlug.get(slug.toLowerCase(Locale.ROOT));
            if (resolved == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown category_slug: " + slug);
            }
            return resolved;
        }
        return fallback;
    }

    private void assertBarcodeAvailable(String catalogId, String barcode, String status, String excludeId) {
        String normalized = blankToNull(barcode);
        if (normalized == null || GlobalProductStatus.ARCHIVED.equals(status)) {
            return;
        }
        long conflicts = excludeId == null
                ? globalProductRepository.countByCatalogIdAndBarcodeAndStatusNot(
                        catalogId, normalized, GlobalProductStatus.ARCHIVED)
                : globalProductRepository.countByCatalogIdAndBarcodeAndStatusNotAndIdNot(
                        catalogId, normalized, GlobalProductStatus.ARCHIVED, excludeId);
        if (conflicts > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Another non-archived global product already uses barcode " + normalized);
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

    private static boolean parseBoolean(String raw, boolean fallback) {
        String value = blankToNull(raw);
        if (value == null) {
            return fallback;
        }
        return "1".equals(value)
                || "true".equalsIgnoreCase(value)
                || "yes".equalsIgnoreCase(value)
                || "y".equalsIgnoreCase(value);
    }

    private static BigDecimal parseDecimal(String raw, BigDecimal fallback) {
        String value = blankToNull(raw);
        if (value == null) {
            return fallback;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid decimal: " + value);
        }
    }

    private static Integer parseInteger(String raw, Integer fallback) {
        String value = blankToNull(raw);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid integer: " + value);
        }
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private enum ImportAction {
        CREATED,
        UPDATED,
        SKIPPED
    }
}
