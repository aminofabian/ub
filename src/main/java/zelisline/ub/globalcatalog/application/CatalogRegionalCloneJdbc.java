package zelisline.ub.globalcatalog.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Clones a published global catalog into another catalog while scrubbing absolute
 * recommended buy/sell prices (keep / derive margin only). Used to seed regional
 * catalogs without copying KE shilling amounts into UGX (etc.).
 */
public final class CatalogRegionalCloneJdbc {

    public static final BigDecimal DEFAULT_MARGIN_PCT = new BigDecimal("25.00");

    /** Matches {@code global_products.suggested_margin_pct DECIMAL(5,2)}. */
    static final BigDecimal MARGIN_PCT_MIN = new BigDecimal("-999.99");
    static final BigDecimal MARGIN_PCT_MAX = new BigDecimal("999.99");

    private CatalogRegionalCloneJdbc() {
    }

    public record CloneStats(
            int categories,
            int products,
            int packs,
            int packItems,
            boolean skipped
    ) {
    }

    /** Counts from a hard wipe of catalog-scoped content (catalog shell kept). */
    public record PurgeStats(
            int deletedSupplierLinkCount,
            int deletedImageCount,
            int deletedPackItemCount,
            int deletedPackCount,
            int deletedProductCount,
            int deletedCategoryCount,
            int deletedSupplierTemplateCount
    ) {
    }

    public static CloneStats cloneScrubbingAbsolutePrices(
            Connection connection,
            String sourceCatalogCode,
            String targetCatalogCode
    ) throws SQLException {
        String sourceId = requireCatalogId(connection, sourceCatalogCode);
        String targetId = requireCatalogId(connection, targetCatalogCode);

        if (countProducts(connection, targetId) > 0) {
            return new CloneStats(0, 0, 0, 0, true);
        }

        // Safe retry after a failed prior attempt that rolled back products but
        // left empty packs/categories (or after a partial non-transactional write).
        clearCatalogContent(connection, targetId);

        Map<String, String> categoryMap = cloneCategories(connection, sourceId, targetId);
        Map<String, String> productMap = cloneProducts(connection, sourceId, targetId, categoryMap);
        Map<String, String> packMap = clonePacks(connection, sourceId, targetId);
        int packItems = clonePackItems(connection, packMap, productMap);

        return new CloneStats(
                categoryMap.size(),
                productMap.size(),
                packMap.size(),
                packItems,
                false
        );
    }

    /**
     * Prefer existing suggested margin; else derive from KE buy/sell; else default.
     * Absolute buy/sell are never returned for the clone target.
     * Result is always clamped to DECIMAL(5,2) so odd KE buy/sell ratios cannot
     * blow up the insert (e.g. buy ≪ sell → five-digit “margin”).
     */
    static BigDecimal resolveMarginPct(
            BigDecimal suggestedMarginPct,
            BigDecimal recommendedBuyingPrice,
            BigDecimal recommendedSellingPrice
    ) {
        BigDecimal raw;
        if (suggestedMarginPct != null) {
            raw = suggestedMarginPct;
        } else if (recommendedBuyingPrice != null
                && recommendedBuyingPrice.compareTo(BigDecimal.ZERO) > 0
                && recommendedSellingPrice != null) {
            raw = recommendedSellingPrice
                    .subtract(recommendedBuyingPrice)
                    .multiply(new BigDecimal("100"))
                    .divide(recommendedBuyingPrice, 2, RoundingMode.HALF_UP);
        } else {
            raw = DEFAULT_MARGIN_PCT;
        }
        return clampMarginPct(raw);
    }

    static BigDecimal clampMarginPct(BigDecimal value) {
        if (value == null) {
            return DEFAULT_MARGIN_PCT;
        }
        if (value.compareTo(MARGIN_PCT_MIN) < 0) {
            return MARGIN_PCT_MIN;
        }
        if (value.compareTo(MARGIN_PCT_MAX) > 0) {
            return MARGIN_PCT_MAX;
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String requireCatalogId(Connection connection, String code) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id FROM global_catalogs WHERE code = ? LIMIT 1")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Catalog not found: " + code);
                }
                return rs.getString(1);
            }
        }
    }

    private static int countProducts(Connection connection, String catalogId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM global_products WHERE catalog_id = ?")) {
            ps.setString(1, catalogId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void clearCatalogContent(Connection connection, String catalogId) throws SQLException {
        purgeCatalogContent(connection, catalogId);
    }

    /**
     * Hard-deletes all content belonging to a catalog. Keeps the {@code global_catalogs} row.
     * Caller must ensure no {@code items.global_product_source_id} still references products
     * in this catalog (FK has no cascade).
     */
    public static PurgeStats purgeCatalogContent(Connection connection, String catalogId) throws SQLException {
        int deletedSupplierLinks;
        int deletedImages;
        int deletedPackItems;
        int deletedPacks;
        int deletedProducts;
        int deletedCategories;
        int deletedSupplierTemplates;

        try (PreparedStatement deleteSupplierLinks = connection.prepareStatement(
                """
                        DELETE FROM global_product_supplier_links
                        WHERE global_product_id IN (
                          SELECT id FROM global_products WHERE catalog_id = ?
                        )
                        """);
             PreparedStatement deleteImages = connection.prepareStatement(
                     """
                             DELETE FROM global_product_images
                             WHERE global_product_id IN (
                               SELECT id FROM global_products WHERE catalog_id = ?
                             )
                             """);
             PreparedStatement clearVariants = connection.prepareStatement(
                     """
                             UPDATE global_products
                                SET variant_of_global_product_id = NULL
                              WHERE catalog_id = ?
                             """);
             PreparedStatement deletePackItems = connection.prepareStatement(
                     """
                             DELETE FROM global_product_pack_items
                             WHERE pack_id IN (
                               SELECT id FROM global_product_packs WHERE catalog_id = ?
                             )
                             """);
             PreparedStatement deletePacks = connection.prepareStatement(
                     "DELETE FROM global_product_packs WHERE catalog_id = ?");
             PreparedStatement deleteProducts = connection.prepareStatement(
                     "DELETE FROM global_products WHERE catalog_id = ?");
             PreparedStatement clearParents = connection.prepareStatement(
                     "UPDATE global_categories SET parent_id = NULL WHERE catalog_id = ?");
             PreparedStatement deleteCategories = connection.prepareStatement(
                     "DELETE FROM global_categories WHERE catalog_id = ?");
             PreparedStatement deleteSupplierTemplates = connection.prepareStatement(
                     "DELETE FROM global_supplier_templates WHERE catalog_id = ?")) {
            deleteSupplierLinks.setString(1, catalogId);
            deletedSupplierLinks = deleteSupplierLinks.executeUpdate();
            deleteImages.setString(1, catalogId);
            deletedImages = deleteImages.executeUpdate();
            clearVariants.setString(1, catalogId);
            clearVariants.executeUpdate();
            deletePackItems.setString(1, catalogId);
            deletedPackItems = deletePackItems.executeUpdate();
            deletePacks.setString(1, catalogId);
            deletedPacks = deletePacks.executeUpdate();
            deleteProducts.setString(1, catalogId);
            deletedProducts = deleteProducts.executeUpdate();
            clearParents.setString(1, catalogId);
            clearParents.executeUpdate();
            deleteCategories.setString(1, catalogId);
            deletedCategories = deleteCategories.executeUpdate();
            deleteSupplierTemplates.setString(1, catalogId);
            deletedSupplierTemplates = deleteSupplierTemplates.executeUpdate();
        }

        return new PurgeStats(
                deletedSupplierLinks,
                deletedImages,
                deletedPackItems,
                deletedPacks,
                deletedProducts,
                deletedCategories,
                deletedSupplierTemplates
        );
    }

    private static Map<String, String> cloneCategories(
            Connection connection,
            String sourceId,
            String targetId
    ) throws SQLException {
        Map<String, String> map = new HashMap<>();
        try (PreparedStatement select = connection.prepareStatement(
                """
                        SELECT id, parent_id, name, slug, position, tenant_category_slug_hint,
                               image_url, active
                        FROM global_categories
                        WHERE catalog_id = ?
                        """);
             PreparedStatement insert = connection.prepareStatement(
                     """
                             INSERT INTO global_categories (
                               id, catalog_id, parent_id, name, slug, position,
                               tenant_category_slug_hint, image_url, active
                             ) VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?)
                             """)) {
            select.setString(1, sourceId);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    String srcId = rs.getString("id");
                    String dstId = UUID.randomUUID().toString();
                    map.put(srcId, dstId);
                    insert.setString(1, dstId);
                    insert.setString(2, targetId);
                    insert.setString(3, rs.getString("name"));
                    insert.setString(4, rs.getString("slug"));
                    insert.setInt(5, rs.getInt("position"));
                    insert.setString(6, rs.getString("tenant_category_slug_hint"));
                    insert.setString(7, rs.getString("image_url"));
                    insert.setBoolean(8, rs.getBoolean("active"));
                    insert.addBatch();
                }
            }
            insert.executeBatch();
        }

        try (PreparedStatement selectParents = connection.prepareStatement(
                "SELECT id, parent_id FROM global_categories WHERE catalog_id = ? AND parent_id IS NOT NULL");
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE global_categories SET parent_id = ? WHERE id = ?")) {
            selectParents.setString(1, sourceId);
            try (ResultSet rs = selectParents.executeQuery()) {
                while (rs.next()) {
                    String dstId = map.get(rs.getString("id"));
                    String dstParent = map.get(rs.getString("parent_id"));
                    if (dstId == null || dstParent == null) {
                        continue;
                    }
                    update.setString(1, dstParent);
                    update.setString(2, dstId);
                    update.addBatch();
                }
            }
            update.executeBatch();
        }
        return map;
    }

    private static Map<String, String> cloneProducts(
            Connection connection,
            String sourceId,
            String targetId,
            Map<String, String> categoryMap
    ) throws SQLException {
        Map<String, String> map = new HashMap<>();
        Set<String> seenBarcodes = new HashSet<>();
        collectExistingBarcodes(connection, targetId, seenBarcodes);
        boolean hasImagePublicId = columnExists(connection, "global_products", "image_public_id");
        boolean hasDedupBarcode = columnExists(connection, "global_products", "dedup_barcode");
        String selectSql = """
                SELECT id, global_category_id, sku_template, name, brand, size, description,
                       barcode, unit_type, is_weighed, is_sellable, is_stocked,
                       recommended_buying_price, recommended_selling_price, suggested_margin_pct,
                       default_reorder_level, default_reorder_qty, default_min_stock_level,
                       has_expiry, expires_after_days, image_url,
                       %s
                       item_type_key_hint, status, sort_order
                FROM global_products
                WHERE catalog_id = ?
                ORDER BY CASE WHEN LOWER(TRIM(status)) = 'published' THEN 0 ELSE 1 END,
                         sort_order ASC,
                         id ASC
                """.formatted(hasImagePublicId ? "image_public_id," : "");
        String insertSql = hasDedupBarcode
                ? (hasImagePublicId
                ? """
                        INSERT INTO global_products (
                          id, catalog_id, global_category_id, sku_template, name, brand, size, description,
                          barcode, unit_type, is_weighed, is_sellable, is_stocked,
                          recommended_buying_price, recommended_selling_price, suggested_margin_pct,
                          default_reorder_level, default_reorder_qty, default_min_stock_level,
                          has_expiry, expires_after_days, image_url, image_public_id, dedup_barcode,
                          item_type_key_hint, status, sort_order
                        ) VALUES (
                          ?, ?, ?, ?, ?, ?, ?, ?,
                          ?, ?, ?, ?, ?,
                          NULL, NULL, ?,
                          ?, ?, ?,
                          ?, ?, ?, ?, ?,
                          ?, ?, ?
                        )
                        """
                : """
                        INSERT INTO global_products (
                          id, catalog_id, global_category_id, sku_template, name, brand, size, description,
                          barcode, unit_type, is_weighed, is_sellable, is_stocked,
                          recommended_buying_price, recommended_selling_price, suggested_margin_pct,
                          default_reorder_level, default_reorder_qty, default_min_stock_level,
                          has_expiry, expires_after_days, image_url, dedup_barcode,
                          item_type_key_hint, status, sort_order
                        ) VALUES (
                          ?, ?, ?, ?, ?, ?, ?, ?,
                          ?, ?, ?, ?, ?,
                          NULL, NULL, ?,
                          ?, ?, ?,
                          ?, ?, ?, ?,
                          ?, ?, ?
                        )
                        """)
                : (hasImagePublicId
                ? """
                        INSERT INTO global_products (
                          id, catalog_id, global_category_id, sku_template, name, brand, size, description,
                          barcode, unit_type, is_weighed, is_sellable, is_stocked,
                          recommended_buying_price, recommended_selling_price, suggested_margin_pct,
                          default_reorder_level, default_reorder_qty, default_min_stock_level,
                          has_expiry, expires_after_days, image_url, image_public_id,
                          item_type_key_hint, status, sort_order
                        ) VALUES (
                          ?, ?, ?, ?, ?, ?, ?, ?,
                          ?, ?, ?, ?, ?,
                          NULL, NULL, ?,
                          ?, ?, ?,
                          ?, ?, ?, ?,
                          ?, ?, ?
                        )
                        """
                : """
                        INSERT INTO global_products (
                          id, catalog_id, global_category_id, sku_template, name, brand, size, description,
                          barcode, unit_type, is_weighed, is_sellable, is_stocked,
                          recommended_buying_price, recommended_selling_price, suggested_margin_pct,
                          default_reorder_level, default_reorder_qty, default_min_stock_level,
                          has_expiry, expires_after_days, image_url,
                          item_type_key_hint, status, sort_order
                        ) VALUES (
                          ?, ?, ?, ?, ?, ?, ?, ?,
                          ?, ?, ?, ?, ?,
                          NULL, NULL, ?,
                          ?, ?, ?,
                          ?, ?, ?,
                          ?, ?, ?
                        )
                        """);

        // One row at a time: MySQL batch rewrite has produced duplicate dedup_barcode
        // collisions even when parameters looked unique per addBatch() call.
        try (PreparedStatement select = connection.prepareStatement(selectSql);
             PreparedStatement insert = connection.prepareStatement(insertSql)) {
            select.setString(1, sourceId);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    String srcId = rs.getString("id");
                    String dstId = UUID.randomUUID().toString();
                    map.put(srcId, dstId);

                    String srcCat = rs.getString("global_category_id");
                    String dstCat = srcCat == null ? null : categoryMap.get(srcCat);
                    BigDecimal margin = resolveMarginPct(
                            rs.getBigDecimal("suggested_margin_pct"),
                            rs.getBigDecimal("recommended_buying_price"),
                            rs.getBigDecimal("recommended_selling_price")
                    );
                    String status = rs.getString("status");
                    String barcode = uniqueBarcodeForClone(
                            rs.getString("barcode"),
                            status,
                            seenBarcodes
                    );
                    bindProductInsert(
                            insert,
                            rs,
                            dstId,
                            targetId,
                            dstCat,
                            margin,
                            barcode,
                            status,
                            hasImagePublicId,
                            hasDedupBarcode
                    );
                    try {
                        insert.executeUpdate();
                    } catch (SQLException ex) {
                        if (!hasDedupBarcode || !isDuplicateDedupBarcode(ex) || barcode == null) {
                            throw ex;
                        }
                        // Collided with an unexpected existing/race row — keep the product, drop barcode.
                        bindProductInsert(
                                insert,
                                rs,
                                dstId,
                                targetId,
                                dstCat,
                                margin,
                                null,
                                status,
                                hasImagePublicId,
                                hasDedupBarcode
                        );
                        insert.executeUpdate();
                    }
                }
            }
        }
        return map;
    }

    private static void bindProductInsert(
            PreparedStatement insert,
            ResultSet rs,
            String dstId,
            String targetId,
            String dstCat,
            BigDecimal margin,
            String barcode,
            String status,
            boolean hasImagePublicId,
            boolean hasDedupBarcode
    ) throws SQLException {
        int i = 1;
        insert.setString(i++, dstId);
        insert.setString(i++, targetId);
        if (dstCat == null) {
            insert.setNull(i++, Types.VARCHAR);
        } else {
            insert.setString(i++, dstCat);
        }
        insert.setString(i++, rs.getString("sku_template"));
        insert.setString(i++, rs.getString("name"));
        insert.setString(i++, rs.getString("brand"));
        insert.setString(i++, rs.getString("size"));
        insert.setString(i++, rs.getString("description"));
        insert.setString(i++, barcode);
        insert.setString(i++, rs.getString("unit_type"));
        insert.setBoolean(i++, rs.getBoolean("is_weighed"));
        insert.setBoolean(i++, rs.getBoolean("is_sellable"));
        insert.setBoolean(i++, rs.getBoolean("is_stocked"));
        insert.setBigDecimal(i++, margin);
        setNullableBigDecimal(insert, i++, rs.getBigDecimal("default_reorder_level"));
        setNullableBigDecimal(insert, i++, rs.getBigDecimal("default_reorder_qty"));
        setNullableBigDecimal(insert, i++, rs.getBigDecimal("default_min_stock_level"));
        insert.setBoolean(i++, rs.getBoolean("has_expiry"));
        Integer expires = (Integer) rs.getObject("expires_after_days");
        if (expires == null) {
            insert.setNull(i++, Types.INTEGER);
        } else {
            insert.setInt(i++, expires);
        }
        insert.setString(i++, rs.getString("image_url"));
        if (hasImagePublicId) {
            insert.setString(i++, rs.getString("image_public_id"));
        }
        if (hasDedupBarcode) {
            boolean archived = status != null && "archived".equalsIgnoreCase(status.trim());
            insert.setString(i++, archived || barcode == null ? null : barcode);
        }
        insert.setString(i++, rs.getString("item_type_key_hint"));
        insert.setString(i++, status);
        insert.setInt(i, rs.getInt("sort_order"));
    }

    private static void collectExistingBarcodes(
            Connection connection,
            String catalogId,
            Set<String> seenBarcodes
    ) throws SQLException {
        boolean hasDedup = columnExists(connection, "global_products", "dedup_barcode");
        String sql = hasDedup
                ? """
                        SELECT dedup_barcode, barcode FROM global_products
                        WHERE catalog_id = ?
                        """
                : """
                        SELECT barcode FROM global_products
                        WHERE catalog_id = ?
                        """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, catalogId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (hasDedup) {
                        rememberBarcode(seenBarcodes, rs.getString("dedup_barcode"));
                    }
                    rememberBarcode(seenBarcodes, rs.getString("barcode"));
                }
            }
        }
    }

    private static void rememberBarcode(Set<String> seenBarcodes, String barcode) {
        if (barcode == null) {
            return;
        }
        String trimmed = barcode.trim();
        if (!trimmed.isEmpty()) {
            seenBarcodes.add(trimmed);
        }
    }

    private static boolean isDuplicateDedupBarcode(SQLException ex) {
        String message = ex.getMessage();
        return ex.getErrorCode() == 1062
                && message != null
                && message.contains("uq_global_products_dedup_barcode");
    }

    /**
     * First row keeps a barcode; later clones of the same code get {@code null}
     * so {@code uq_global_products_dedup_barcode} cannot fire on KE data that
     * already has duplicate barcodes across rows (any status).
     */
    static String uniqueBarcodeForClone(String barcode, String status, Set<String> seenBarcodes) {
        if (barcode == null) {
            return null;
        }
        String trimmed = barcode.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (!seenBarcodes.add(trimmed)) {
            return null;
        }
        return trimmed;
    }

    private static Map<String, String> clonePacks(
            Connection connection,
            String sourceId,
            String targetId
    ) throws SQLException {
        Map<String, String> map = new HashMap<>();
        boolean hasStoreKit = columnExists(connection, "global_product_packs", "store_kit_id");
        String selectSql = hasStoreKit
                ? """
                        SELECT id, code, name, description, store_kit_id, status, sort_order
                        FROM global_product_packs WHERE catalog_id = ?
                        """
                : """
                        SELECT id, code, name, description, status, sort_order
                        FROM global_product_packs WHERE catalog_id = ?
                        """;
        String insertSql = hasStoreKit
                ? """
                        INSERT INTO global_product_packs (
                          id, catalog_id, code, name, description, store_kit_id, status, sort_order
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """
                : """
                        INSERT INTO global_product_packs (
                          id, catalog_id, code, name, description, status, sort_order
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """;
        try (PreparedStatement select = connection.prepareStatement(selectSql);
             PreparedStatement insert = connection.prepareStatement(insertSql)) {
            select.setString(1, sourceId);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    String srcId = rs.getString("id");
                    String dstId = UUID.randomUUID().toString();
                    map.put(srcId, dstId);
                    int i = 1;
                    insert.setString(i++, dstId);
                    insert.setString(i++, targetId);
                    insert.setString(i++, rs.getString("code"));
                    insert.setString(i++, rs.getString("name"));
                    insert.setString(i++, rs.getString("description"));
                    if (hasStoreKit) {
                        insert.setString(i++, rs.getString("store_kit_id"));
                    }
                    insert.setString(i++, rs.getString("status"));
                    insert.setInt(i, rs.getInt("sort_order"));
                    insert.addBatch();
                }
            }
            insert.executeBatch();
        }
        return map;
    }

    private static int clonePackItems(
            Connection connection,
            Map<String, String> packMap,
            Map<String, String> productMap
    ) throws SQLException {
        if (packMap.isEmpty() || productMap.isEmpty()) {
            return 0;
        }
        int count = 0;
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT pack_id, global_product_id, sort_order FROM global_product_pack_items WHERE pack_id = ?");
             PreparedStatement insert = connection.prepareStatement(
                     """
                             INSERT INTO global_product_pack_items (pack_id, global_product_id, sort_order)
                             VALUES (?, ?, ?)
                             """)) {
            for (Map.Entry<String, String> pack : packMap.entrySet()) {
                select.setString(1, pack.getKey());
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        String dstProduct = productMap.get(rs.getString("global_product_id"));
                        if (dstProduct == null) {
                            continue;
                        }
                        insert.setString(1, pack.getValue());
                        insert.setString(2, dstProduct);
                        insert.setInt(3, rs.getInt("sort_order"));
                        insert.addBatch();
                        count++;
                    }
                }
            }
            insert.executeBatch();
        }
        return count;
    }

    private static void setNullableBigDecimal(
            PreparedStatement ps,
            int index,
            BigDecimal value
    ) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DECIMAL);
        } else {
            ps.setBigDecimal(index, value);
        }
    }

    private static boolean columnExists(
            Connection connection,
            String table,
            String column
    ) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getColumns(null, null, table, column)) {
            if (rs.next()) {
                return true;
            }
        }
        // H2 / some drivers are case-sensitive on metadata
        try (ResultSet rs = connection.getMetaData().getColumns(
                null, null, table.toUpperCase(), column.toUpperCase())) {
            return rs.next();
        }
    }
}
