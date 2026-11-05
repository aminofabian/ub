package zelisline.ub.globalcatalog.application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.globalcatalog.domain.GlobalCatalog;
import zelisline.ub.globalcatalog.domain.GlobalCategory;
import zelisline.ub.globalcatalog.domain.GlobalProduct;
import zelisline.ub.globalcatalog.domain.GlobalProductStatus;
import zelisline.ub.globalcatalog.repository.GlobalCatalogRepository;
import zelisline.ub.globalcatalog.repository.GlobalCategoryRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductRepository;

/**
 * Super-admin CSV export for global catalog products (v2, GLOBAL_CATALOG_IMPORT_SCOPE §5.8):
 *
 * <ul>
 *   <li>Whole catalog by default — filters apply only when explicitly requested, so an
 *       export is a backup, not a view.</li>
 *   <li>No {@code image_url} column: import rejects it, so export must not emit it.</li>
 *   <li>{@code category_name} + {@code version} are included so export → import round-trips
 *       and stale rows are detectable.</li>
 *   <li>The filename names the catalog and the day.</li>
 * </ul>
 *
 * <p>Import lives in {@link SuperAdminGlobalCatalogImportService}.
 */
@Service
@RequiredArgsConstructor
public class SuperAdminGlobalCatalogCsvService {

    private static final String DEFAULT_CATALOG_CODE = "default";

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
            "category_name",
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
            "version"
    };

    private final GlobalCatalogRepository globalCatalogRepository;
    private final GlobalCategoryRepository globalCategoryRepository;
    private final GlobalProductRepository globalProductRepository;

    public record ExportFile(String fileName, byte[] content) {
    }

    @Transactional(readOnly = true)
    public ExportFile exportCsv(String catalogId, String status, boolean missingImage, boolean applyFilters) {
        GlobalCatalog catalog = requireCatalog(catalogId);
        String statusFilter = applyFilters ? blankToNull(status) : null;
        boolean missingImageFilter = applyFilters && missingImage;
        if (statusFilter != null && !GlobalProductStatus.isAllowed(statusFilter)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid status filter");
        }

        List<GlobalProduct> products = globalProductRepository.findByCatalogId(catalog.getId()).stream()
                .filter(p -> statusFilter == null || statusFilter.equals(p.getStatus()))
                .filter(p -> !missingImageFilter || blankToNull(p.getImageUrl()) == null)
                .sorted((a, b) -> {
                    int bySort = Integer.compare(a.getSortOrder(), b.getSortOrder());
                    if (bySort != 0) {
                        return bySort;
                    }
                    return a.getName().compareToIgnoreCase(b.getName());
                })
                .toList();

        List<GlobalCategory> categories = globalCategoryRepository
                .findByCatalogIdAndActiveTrueOrderByPositionAsc(catalog.getId());
        Map<String, String> categorySlugById = categories.stream()
                .collect(java.util.stream.Collectors.toMap(GlobalCategory::getId, GlobalCategory::getSlug, (a, b) -> a));
        Map<String, String> categoryNameById = categories.stream()
                .collect(java.util.stream.Collectors.toMap(GlobalCategory::getId, GlobalCategory::getName, (a, b) -> a));

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
                            nullToEmpty(categoryNameById.get(product.getGlobalCategoryId())),
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
                            product.getVersion());
                }
                printer.flush();
            }
            String fileName = "global-catalog-" + catalog.getCode() + "-"
                    + LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE)
                    + (applyFilters ? "-filtered" : "")
                    + ".csv";
            return new ExportFile(fileName, out.toByteArray());
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not build CSV export");
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
}
