package zelisline.ub.globalcatalog.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

class CatalogRegionalCloneJdbcTest {

    @Test
    void resolveMargin_prefersSuggested() {
        assertEquals(
                new BigDecimal("30.00"),
                CatalogRegionalCloneJdbc.resolveMarginPct(
                        new BigDecimal("30.00"),
                        new BigDecimal("100"),
                        new BigDecimal("200")
                )
        );
    }

    @Test
    void resolveMargin_derivesFromBuySell() {
        assertEquals(
                new BigDecimal("25.00"),
                CatalogRegionalCloneJdbc.resolveMarginPct(
                        null,
                        new BigDecimal("80"),
                        new BigDecimal("100")
                )
        );
    }

    @Test
    void resolveMargin_defaultsWhenMissing() {
        assertEquals(
                CatalogRegionalCloneJdbc.DEFAULT_MARGIN_PCT,
                CatalogRegionalCloneJdbc.resolveMarginPct(null, null, null)
        );
    }

    @Test
    void resolveMargin_clampsExtremeDerivedRatio() {
        assertEquals(
                CatalogRegionalCloneJdbc.MARGIN_PCT_MAX,
                CatalogRegionalCloneJdbc.resolveMarginPct(
                        null,
                        new BigDecimal("1"),
                        new BigDecimal("50000")
                )
        );
    }

    @Test
    void resolveMargin_clampsExtremeSuggested() {
        assertEquals(
                CatalogRegionalCloneJdbc.MARGIN_PCT_MAX,
                CatalogRegionalCloneJdbc.resolveMarginPct(
                        new BigDecimal("100000"),
                        null,
                        null
                )
        );
    }

    @Test
    void uniqueBarcode_keepsFirstDropsDuplicate() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        assertEquals(
                "792382650682",
                CatalogRegionalCloneJdbc.uniqueBarcodeForClone("792382650682", "published", seen)
        );
        assertEquals(
                null,
                CatalogRegionalCloneJdbc.uniqueBarcodeForClone("792382650682", "published", seen)
        );
        assertEquals(
                null,
                CatalogRegionalCloneJdbc.uniqueBarcodeForClone("792382650682", "archived", seen)
        );
    }

    @Test
    void clone_allowsDuplicateSourceBarcodesWithDedupIndex() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:ug_clone_dedup;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            try (Statement st = connection.createStatement()) {
                st.execute("""
                        CREATE TABLE global_catalogs (
                          id VARCHAR(36) PRIMARY KEY,
                          code VARCHAR(64) NOT NULL UNIQUE,
                          name VARCHAR(255) NOT NULL,
                          region_code CHAR(2),
                          currency CHAR(3) NOT NULL,
                          status VARCHAR(16) NOT NULL
                        )
                        """);
                st.execute("""
                        CREATE TABLE global_categories (
                          id VARCHAR(36) PRIMARY KEY,
                          catalog_id VARCHAR(36) NOT NULL,
                          parent_id VARCHAR(36),
                          name VARCHAR(255) NOT NULL,
                          slug VARCHAR(191) NOT NULL,
                          position INT NOT NULL,
                          tenant_category_slug_hint VARCHAR(191),
                          image_url VARCHAR(2048),
                          active BOOLEAN NOT NULL
                        )
                        """);
                st.execute("""
                        CREATE TABLE global_products (
                          id VARCHAR(36) PRIMARY KEY,
                          catalog_id VARCHAR(36) NOT NULL,
                          global_category_id VARCHAR(36),
                          sku_template VARCHAR(191),
                          name VARCHAR(500) NOT NULL,
                          brand VARCHAR(255),
                          size VARCHAR(50),
                          description CLOB,
                          barcode VARCHAR(191),
                          unit_type VARCHAR(16) NOT NULL,
                          is_weighed BOOLEAN NOT NULL,
                          is_sellable BOOLEAN NOT NULL,
                          is_stocked BOOLEAN NOT NULL,
                          recommended_buying_price DECIMAL(14,2),
                          recommended_selling_price DECIMAL(14,2),
                          suggested_margin_pct DECIMAL(5,2),
                          default_reorder_level DECIMAL(14,4),
                          default_reorder_qty DECIMAL(14,4),
                          default_min_stock_level DECIMAL(14,4),
                          has_expiry BOOLEAN NOT NULL,
                          expires_after_days INT,
                          image_url VARCHAR(2048),
                          image_public_id VARCHAR(512),
                          dedup_barcode VARCHAR(191),
                          item_type_key_hint VARCHAR(64),
                          status VARCHAR(16) NOT NULL,
                          sort_order INT NOT NULL,
                          UNIQUE (catalog_id, dedup_barcode)
                        )
                        """);
                st.execute("""
                        CREATE TABLE global_product_packs (
                          id VARCHAR(36) PRIMARY KEY,
                          catalog_id VARCHAR(36) NOT NULL,
                          code VARCHAR(64) NOT NULL,
                          name VARCHAR(255) NOT NULL,
                          description CLOB,
                          store_kit_id VARCHAR(64),
                          status VARCHAR(16) NOT NULL,
                          sort_order INT NOT NULL
                        )
                        """);
                st.execute("""
                        CREATE TABLE global_product_pack_items (
                          pack_id VARCHAR(36) NOT NULL,
                          global_product_id VARCHAR(36) NOT NULL,
                          sort_order INT NOT NULL,
                          PRIMARY KEY (pack_id, global_product_id)
                        )
                        """);
                st.execute("""
                        INSERT INTO global_catalogs VALUES
                        ('c-ke', 'default', 'KE', 'KE', 'KES', 'published'),
                        ('c-ug', 'ug-retail', 'UG', 'UG', 'UGX', 'published')
                        """);
                st.execute("""
                        INSERT INTO global_categories VALUES
                        ('cat-1', 'c-ke', NULL, 'Grocery', 'grocery', 0, NULL, NULL, TRUE)
                        """);
                st.execute("""
                        INSERT INTO global_products (
                          id, catalog_id, global_category_id, sku_template, name, brand, size, description,
                          barcode, unit_type, is_weighed, is_sellable, is_stocked,
                          recommended_buying_price, recommended_selling_price, suggested_margin_pct,
                          default_reorder_level, default_reorder_qty, default_min_stock_level,
                          has_expiry, expires_after_days, image_url, image_public_id, dedup_barcode,
                          item_type_key_hint, status, sort_order
                        ) VALUES
                        ('p-1', 'c-ke', 'cat-1', NULL, 'Sugar A', NULL, NULL, NULL,
                         '792382650682', 'each', FALSE, TRUE, TRUE,
                         80.00, 100.00, NULL,
                         NULL, NULL, NULL,
                         FALSE, NULL, NULL, NULL, '792382650682',
                         'goods', 'published', 0),
                        ('p-2', 'c-ke', 'cat-1', NULL, 'Sugar B', NULL, NULL, NULL,
                         '792382650682', 'each', FALSE, TRUE, TRUE,
                         80.00, 100.00, NULL,
                         NULL, NULL, NULL,
                         FALSE, NULL, NULL, NULL, NULL,
                         'goods', 'published', 1),
                        ('p-3', 'c-ke', 'cat-1', NULL, 'Sugar C', NULL, NULL, NULL,
                         '792382650682', 'each', FALSE, TRUE, TRUE,
                         80.00, 100.00, NULL,
                         NULL, NULL, NULL,
                         FALSE, NULL, NULL, NULL, NULL,
                         'goods', 'archived', 2)
                        """);
            }

            CatalogRegionalCloneJdbc.CloneStats stats =
                    CatalogRegionalCloneJdbc.cloneScrubbingAbsolutePrices(
                            connection, "default", "ug-retail");
            assertEquals(3, stats.products());
            assertEquals(false, stats.skipped());

            try (var ps = connection.prepareStatement(
                    """
                            SELECT name, barcode, dedup_barcode
                            FROM global_products
                            WHERE catalog_id = 'c-ug'
                            ORDER BY name
                            """);
                 var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("Sugar A", rs.getString(1));
                assertEquals("792382650682", rs.getString(2));
                assertEquals("792382650682", rs.getString(3));
                assertTrue(rs.next());
                assertEquals("Sugar B", rs.getString(1));
                assertEquals(null, rs.getString(2));
                assertEquals(null, rs.getString(3));
                assertTrue(rs.next());
                assertEquals("Sugar C", rs.getString(1));
                assertEquals(null, rs.getString(2));
                assertEquals(null, rs.getString(3));
            }
        }
    }

    @Test
    void cloneScrubsAbsolutePricesAndCopiesPack() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:ug_clone;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            try (Statement st = connection.createStatement()) {
                st.execute("""
                        CREATE TABLE global_catalogs (
                          id VARCHAR(36) PRIMARY KEY,
                          code VARCHAR(64) NOT NULL UNIQUE,
                          name VARCHAR(255) NOT NULL,
                          region_code CHAR(2),
                          currency CHAR(3) NOT NULL,
                          status VARCHAR(16) NOT NULL
                        )
                        """);
                st.execute("""
                        CREATE TABLE global_categories (
                          id VARCHAR(36) PRIMARY KEY,
                          catalog_id VARCHAR(36) NOT NULL,
                          parent_id VARCHAR(36),
                          name VARCHAR(255) NOT NULL,
                          slug VARCHAR(191) NOT NULL,
                          position INT NOT NULL,
                          tenant_category_slug_hint VARCHAR(191),
                          image_url VARCHAR(2048),
                          active BOOLEAN NOT NULL
                        )
                        """);
                st.execute("""
                        CREATE TABLE global_products (
                          id VARCHAR(36) PRIMARY KEY,
                          catalog_id VARCHAR(36) NOT NULL,
                          global_category_id VARCHAR(36),
                          sku_template VARCHAR(191),
                          name VARCHAR(500) NOT NULL,
                          brand VARCHAR(255),
                          size VARCHAR(50),
                          description CLOB,
                          barcode VARCHAR(191),
                          unit_type VARCHAR(16) NOT NULL,
                          is_weighed BOOLEAN NOT NULL,
                          is_sellable BOOLEAN NOT NULL,
                          is_stocked BOOLEAN NOT NULL,
                          recommended_buying_price DECIMAL(14,2),
                          recommended_selling_price DECIMAL(14,2),
                          suggested_margin_pct DECIMAL(5,2),
                          default_reorder_level DECIMAL(14,4),
                          default_reorder_qty DECIMAL(14,4),
                          default_min_stock_level DECIMAL(14,4),
                          has_expiry BOOLEAN NOT NULL,
                          expires_after_days INT,
                          image_url VARCHAR(2048),
                          item_type_key_hint VARCHAR(64),
                          status VARCHAR(16) NOT NULL,
                          sort_order INT NOT NULL
                        )
                        """);
                st.execute("""
                        CREATE TABLE global_product_packs (
                          id VARCHAR(36) PRIMARY KEY,
                          catalog_id VARCHAR(36) NOT NULL,
                          code VARCHAR(64) NOT NULL,
                          name VARCHAR(255) NOT NULL,
                          description CLOB,
                          store_kit_id VARCHAR(64),
                          status VARCHAR(16) NOT NULL,
                          sort_order INT NOT NULL
                        )
                        """);
                st.execute("""
                        CREATE TABLE global_product_pack_items (
                          pack_id VARCHAR(36) NOT NULL,
                          global_product_id VARCHAR(36) NOT NULL,
                          sort_order INT NOT NULL,
                          PRIMARY KEY (pack_id, global_product_id)
                        )
                        """);
                st.execute("""
                        INSERT INTO global_catalogs VALUES
                        ('c-ke', 'default', 'KE', 'KE', 'KES', 'published'),
                        ('c-ug', 'ug-retail', 'UG', 'UG', 'UGX', 'published')
                        """);
                st.execute("""
                        INSERT INTO global_categories VALUES
                        ('cat-1', 'c-ke', NULL, 'Grocery', 'grocery', 0, NULL, NULL, TRUE)
                        """);
                st.execute("""
                        INSERT INTO global_products (
                          id, catalog_id, global_category_id, sku_template, name, brand, size, description,
                          barcode, unit_type, is_weighed, is_sellable, is_stocked,
                          recommended_buying_price, recommended_selling_price, suggested_margin_pct,
                          default_reorder_level, default_reorder_qty, default_min_stock_level,
                          has_expiry, expires_after_days, image_url, item_type_key_hint, status, sort_order
                        ) VALUES
                        ('p-1', 'c-ke', 'cat-1', NULL, 'Sugar 1kg', NULL, NULL, NULL,
                         '111', 'each', FALSE, TRUE, TRUE,
                         80.00, 100.00, NULL,
                         NULL, NULL, NULL,
                         FALSE, NULL, NULL, 'goods', 'published', 0)
                        """);
                for (int i = 2; i <= 26; i++) {
                    st.execute("""
                            INSERT INTO global_products (
                              id, catalog_id, global_category_id, sku_template, name, brand, size, description,
                              barcode, unit_type, is_weighed, is_sellable, is_stocked,
                              recommended_buying_price, recommended_selling_price, suggested_margin_pct,
                              default_reorder_level, default_reorder_qty, default_min_stock_level,
                              has_expiry, expires_after_days, image_url, item_type_key_hint, status, sort_order
                            ) VALUES
                            ('p-%d', 'c-ke', 'cat-1', NULL, 'Item %d', NULL, NULL, NULL,
                             '%d', 'each', FALSE, TRUE, TRUE,
                             10.00, 12.00, 20.00,
                             NULL, NULL, NULL,
                             FALSE, NULL, NULL, 'goods', 'published', %d)
                            """.formatted(i, i, i, i));
                }
                st.execute("""
                        INSERT INTO global_product_packs VALUES
                        ('pack-1', 'c-ke', 'mini-mart-starter', 'Mini Mart', 'Starter', 'mini-mart', 'published', 0)
                        """);
                for (int i = 1; i <= 26; i++) {
                    st.execute("""
                            INSERT INTO global_product_pack_items VALUES ('pack-1', 'p-%d', %d)
                            """.formatted(i, i));
                }
            }

            CatalogRegionalCloneJdbc.CloneStats stats =
                    CatalogRegionalCloneJdbc.cloneScrubbingAbsolutePrices(
                            connection, "default", "ug-retail");
            assertEquals(1, stats.categories());
            assertEquals(26, stats.products());
            assertEquals(1, stats.packs());
            assertEquals(26, stats.packItems());
            assertEquals(false, stats.skipped());

            try (var ps = connection.prepareStatement(
                    """
                            SELECT recommended_buying_price, recommended_selling_price, suggested_margin_pct
                            FROM global_products WHERE catalog_id = 'c-ug' AND name = 'Sugar 1kg'
                            """);
                 var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(null, rs.getBigDecimal(1));
                assertEquals(null, rs.getBigDecimal(2));
                assertEquals(new BigDecimal("25.00"), rs.getBigDecimal(3));
            }

            CatalogRegionalCloneJdbc.CloneStats second =
                    CatalogRegionalCloneJdbc.cloneScrubbingAbsolutePrices(
                            connection, "default", "ug-retail");
            assertTrue(second.skipped());
        }
    }
}
