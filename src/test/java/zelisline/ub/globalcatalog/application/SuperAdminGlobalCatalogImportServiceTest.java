package zelisline.ub.globalcatalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.ImportIssueResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.ImportPreviewResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.ImportRowPlanResponse;
import zelisline.ub.globalcatalog.application.SuperAdminGlobalCatalogImportService.ImportOptions;
import zelisline.ub.globalcatalog.domain.GlobalCatalog;
import zelisline.ub.globalcatalog.domain.GlobalCategory;
import zelisline.ub.globalcatalog.domain.GlobalProduct;
import zelisline.ub.globalcatalog.repository.GlobalCatalogRepository;
import zelisline.ub.globalcatalog.repository.GlobalCategoryRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductPackItemRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductPackRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductRepository;

/**
 * Planner-level tests for import v2 (GLOBAL_CATALOG_IMPORT_SCOPE §10, unit layer):
 * header aliases, strict booleans, §5.7 absent-vs-blank, §6.3.1 identity, §4.3 parent
 * invariants, manifest mode and pack planning. Preview makes no writes, so everything
 * here runs against mocked repositories.
 */
class SuperAdminGlobalCatalogImportServiceTest {

    private static final String CATALOG_ID = "cat1";

    private GlobalCatalogRepository catalogRepository;
    private GlobalCategoryRepository categoryRepository;
    private GlobalProductRepository productRepository;
    private GlobalProductPackRepository packRepository;
    private GlobalProductPackItemRepository packItemRepository;
    private SuperAdminGlobalCatalogImportService service;

    @BeforeEach
    void setUp() {
        catalogRepository = mock(GlobalCatalogRepository.class);
        categoryRepository = mock(GlobalCategoryRepository.class);
        productRepository = mock(GlobalProductRepository.class);
        packRepository = mock(GlobalProductPackRepository.class);
        packItemRepository = mock(GlobalProductPackItemRepository.class);

        GlobalCatalog catalog = new GlobalCatalog();
        catalog.setId(CATALOG_ID);
        catalog.setCode("default");
        when(catalogRepository.findById(CATALOG_ID)).thenReturn(Optional.of(catalog));

        service = new SuperAdminGlobalCatalogImportService(
                catalogRepository,
                categoryRepository,
                productRepository,
                packRepository,
                packItemRepository,
                new GlobalCatalogProductGraphWriter(productRepository),
                mock(SuperAdminGlobalCatalogImportLineExecutor.class),
                mock(AuditEventPublisher.class),
                mock(AuditEventBuilder.class));
    }

    // ---------------------------------------------------------------- helpers

    private static ImportOptions options() {
        return new ImportOptions(null, false, true, false, false, null);
    }

    private ImportPreviewResponse preview(String csv) {
        return preview(csv, options());
    }

    private ImportPreviewResponse preview(String csv, ImportOptions opts) {
        return service.preview(
                CATALOG_ID,
                new MockMultipartFile("file", "test.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)),
                opts);
    }

    private void givenCategory(String name, String slug) {
        GlobalCategory category = new GlobalCategory();
        category.setId("category-" + slug);
        category.setCatalogId(CATALOG_ID);
        category.setName(name);
        category.setSlug(slug);
        category.setActive(true);
        when(categoryRepository.findByCatalogIdOrderByPositionAsc(CATALOG_ID))
                .thenReturn(List.of(category));
    }

    private GlobalProduct givenExistingProduct(String id, String name, String barcode) {
        GlobalProduct product = new GlobalProduct();
        product.setId(id);
        product.setCatalogId(CATALOG_ID);
        product.setName(name);
        product.setBarcode(barcode);
        product.setStatus("published");
        product.setUnitType("each");
        when(productRepository.findByCatalogId(CATALOG_ID)).thenReturn(List.of(product));
        return product;
    }

    private static List<String> issueCodes(ImportRowPlanResponse row) {
        return row.issues().stream().map(ImportIssueResponse::code).toList();
    }

    // ---------------------------------------------------------------- P0: aliases & strictness

    @Test
    void palmartAliasesMapPricesAndCategoryByDisplayName() {
        givenCategory("Grains & Cereals", "grains-cereals");
        ImportPreviewResponse result = preview("""
                name,sku,selling_price,buying_price,category_name,is_stocked
                Pishori Rice 1kg,RICE-1KG,180,150,Grains & Cereals,1
                """);

        assertThat(result.summary().errors()).isZero();
        assertThat(result.summary().create()).isEqualTo(1);
        assertThat(result.columns().aliased())
                .contains("sku → sku_template", "selling_price → recommended_selling_price");
        ImportRowPlanResponse row = result.rows().get(0);
        assertThat(row.diff()).containsKey("selling_price");
        assertThat(row.diff().get("selling_price").get(1)).isEqualTo("180");
        assertThat(row.diff().get("category_id").get(1)).isEqualTo("category-grains-cereals");
    }

    @Test
    void unknownCategoryBlocksUnlessCreateCategoriesIsEnabled() {
        ImportPreviewResponse blocked = preview("""
                name,category_name
                Soda,Softdrinks
                """);
        assertThat(issueCodes(blocked.rows().get(0))).contains("category.unknown");
        assertThat(blocked.rows().get(0).action()).isEqualTo("error");

        ImportPreviewResponse allowed = preview("""
                name,category_name
                Soda,Softdrinks
                """, new ImportOptions(null, true, true, false, false, null));
        assertThat(allowed.rows().get(0).action()).isEqualTo("create");
        assertThat(issueCodes(allowed.rows().get(0))).contains("category.will_create");
    }

    @Test
    void typoBooleanIsAnErrorNeverSilentlyFalse() {
        // Finding 23: 'ture' used to become false with no warning.
        ImportPreviewResponse result = preview("""
                name,stocked
                Bread,ture
                """);
        assertThat(issueCodes(result.rows().get(0))).contains("product.invalid_stocked");
        assertThat(result.rows().get(0).action()).isEqualTo("error");
    }

    @Test
    void packColumnsWithoutRowTypeFailTheWholeFile() {
        ImportPreviewResponse result = preview("""
                name,pack_code
                Something,MM-STARTER
                """);
        assertThat(result.fileIssues())
                .anyMatch(issue -> "file.row_type_required".equals(issue.code()));
        assertThat(result.rows()).isEmpty();
    }

    // ---------------------------------------------------------------- §5.7 absent vs blank

    @Test
    void absentColumnKeepsStoredValueAndBlankCellClearsIt() {
        GlobalProduct existing = givenExistingProduct("p1", "Rice", "1234567");
        existing.setBrand("OldBrand");

        ImportPreviewResponse absent = preview("""
                name,barcode
                Rice,1234567
                """);
        assertThat(absent.rows().get(0).action()).isEqualTo("unchanged");

        ImportPreviewResponse blank = preview("""
                name,barcode,brand
                Rice,1234567,
                """);
        ImportRowPlanResponse row = blank.rows().get(0);
        assertThat(row.action()).isEqualTo("update");
        assertThat(row.diff().get("brand")).isEqualTo(List.of("OldBrand", ""));
    }

    // ---------------------------------------------------------------- §6.3.1 identity

    @Test
    void idAndBarcodePointingAtDifferentProductsIsAConflict() {
        GlobalProduct first = new GlobalProduct();
        first.setId("p1");
        first.setCatalogId(CATALOG_ID);
        first.setName("First");
        first.setStatus("published");
        GlobalProduct second = new GlobalProduct();
        second.setId("p2");
        second.setCatalogId(CATALOG_ID);
        second.setName("Second");
        second.setBarcode("999");
        second.setStatus("published");
        when(productRepository.findByCatalogId(CATALOG_ID)).thenReturn(List.of(first, second));

        ImportPreviewResponse result = preview("""
                id,name,barcode
                p1,Whatever,999
                """);
        assertThat(issueCodes(result.rows().get(0))).contains("product.identity_conflict");
    }

    @Test
    void staleVersionBlocksUnlessForced() {
        GlobalProduct existing = givenExistingProduct("p1", "Rice", "1234567");
        existing.setVersion(5);

        ImportPreviewResponse stale = preview("""
                name,barcode,version
                Rice Premium,1234567,3
                """);
        assertThat(issueCodes(stale.rows().get(0))).contains("product.stale_version");

        ImportPreviewResponse forced = preview("""
                name,barcode,version
                Rice Premium,1234567,3
                """, new ImportOptions(null, false, true, true, false, null));
        assertThat(forced.rows().get(0).action()).isEqualTo("update");
    }

    // ---------------------------------------------------------------- parents (§4.3)

    @Test
    void parentSkuResolvesWithinTheSameFile() {
        ImportPreviewResponse result = preview("""
                name,sku_template,parent_sku,is_package_variant,packaging_unit_name,packaging_unit_qty
                Coca-Cola 500ml,COKE-500,,,,
                Coca-Cola Tray,COKE-TRAY,COKE-500,true,Tray,12
                """);

        assertThat(result.mode()).isEqualTo("products+parents");
        assertThat(result.summary().errors()).isZero();
        assertThat(result.summary().parentLinks()).isEqualTo(1);
        ImportRowPlanResponse child = result.rows().get(1);
        assertThat(child.parent()).isNotNull();
        assertThat(child.parent().resolution()).isEqualTo("in_file");
        // The linked package variant defaults to stocked=false (shares parent stock).
        assertThat(child.diff().get("stocked").get(1)).isEqualTo("false");
    }

    @Test
    void publishedChildUnderDraftParentBlocksUnlessExplicitlyAllowed() {
        String csv = """
                row_type,ref,name,parent_ref,status
                product,BASE,Coca-Cola 500ml,,draft
                product,TRAY,Coca-Cola Tray,BASE,published
                """;
        ImportPreviewResponse blocked = preview(csv);
        assertThat(issueCodes(blocked.rows().get(1))).contains("relation.parent_not_published");
        assertThat(blocked.rows().get(1).action()).isEqualTo("error");

        ImportPreviewResponse allowed = preview(csv,
                new ImportOptions(null, false, true, false, true, null));
        assertThat(allowed.rows().get(1).action()).isEqualTo("create");
        assertThat(allowed.summary().parentLinks()).isEqualTo(1);
    }

    @Test
    void nestingDeeperThanOneLevelIsRejected() {
        ImportPreviewResponse result = preview("""
                row_type,ref,name,parent_ref
                product,A,Grandparent,
                product,B,Parent,A
                product,C,Child,B
                """);
        assertThat(issueCodes(result.rows().get(2))).contains("relation.parent_is_variant");
    }

    @Test
    void missingParentWithParentNameBecomesASyntheticParent() {
        ImportPreviewResponse result = preview("""
                name,parent_sku,parent_name,status
                Coca-Cola Tray,NO-SUCH-SKU,Coca-Cola 500ml,draft
                """);
        ImportRowPlanResponse row = result.rows().get(0);
        assertThat(row.action()).isEqualTo("create");
        assertThat(row.parent()).isNotNull();
        assertThat(row.parent().resolution()).isEqualTo("synthetic");
        assertThat(result.summary().syntheticParents()).isEqualTo(1);
        assertThat(issueCodes(row)).contains("relation.parent_will_create");
    }

    @Test
    void missingParentWithoutParentNameIsAnError() {
        ImportPreviewResponse result = preview("""
                name,parent_sku
                Coca-Cola Tray,NO-SUCH-SKU
                """);
        assertThat(issueCodes(result.rows().get(0))).contains("relation.parent_not_found");
    }

    // ---------------------------------------------------------------- manifest + packs

    @Test
    void manifestPlansParentsVariantsAndPackMembershipTogether() {
        givenCategory("Drinks", "drinks");
        ImportPreviewResponse result = preview("""
                row_type,ref,name,barcode,parent_ref,is_package_variant,packaging_unit_name,packaging_unit_qty,category_name,status,pack_code,pack_name,pack_status,pack_replace,pack_ref,product_ref
                product,COKE-500,Coca-Cola 500ml,5449000000996,,,,,Drinks,published,,,,,,
                product,COKE-500-TRAY,Coca-Cola 500ml Tray,5449000001009,COKE-500,true,Tray,12,Drinks,published,,,,,,
                pack,MM-STARTER,,,,,,,,,MM-STARTER,Mini-mart starter pack,published,true,,
                pack_item,,,,,,,,,,,,,,MM-STARTER,COKE-500
                pack_item,,,,,,,,,,,,,,MM-STARTER,COKE-500-TRAY
                """);

        assertThat(result.mode()).isEqualTo("manifest");
        assertThat(result.summary().errors()).isZero();
        assertThat(result.summary().create()).isEqualTo(2);
        assertThat(result.summary().parentLinks()).isEqualTo(1);
        assertThat(result.summary().packCreate()).isEqualTo(1);
        assertThat(result.summary().packMemberships()).isEqualTo(2);
        assertThat(result.rowTypeCounts())
                .containsEntry("product", 2)
                .containsEntry("pack", 1)
                .containsEntry("pack_item", 2);
        assertThat(result.packs()).hasSize(1);
        assertThat(result.packs().get(0).memberCount()).isEqualTo(2);
        assertThat(result.packs().get(0).replace()).isTrue();
    }

    @Test
    void duplicatePackMembersCollapseWithAWarning() {
        ImportPreviewResponse result = preview("""
                row_type,ref,name,pack_code,pack_name,pack_ref,product_ref
                product,P1,Bread,,,,
                pack,PK,,BASKET,Basket,,
                pack_item,,,,,PK,P1
                pack_item,,,,,PK,P1
                """);
        assertThat(result.packs().get(0).memberCount()).isEqualTo(1);
        assertThat(result.packs().get(0).issues())
                .anyMatch(issue -> "pack.duplicate_member".equals(issue.code()));
    }
}
