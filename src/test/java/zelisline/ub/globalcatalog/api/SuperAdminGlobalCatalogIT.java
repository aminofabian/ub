package zelisline.ub.globalcatalog.api;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.globalcatalog.domain.GlobalCatalog;
import zelisline.ub.globalcatalog.domain.GlobalProduct;
import zelisline.ub.globalcatalog.domain.GlobalProductPack;
import zelisline.ub.globalcatalog.domain.GlobalProductStatus;
import zelisline.ub.globalcatalog.repository.GlobalCatalogRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductPackItemRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductPackRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductSupplierLinkRepository;
import zelisline.ub.globalcatalog.repository.GlobalSupplierTemplateRepository;
import zelisline.ub.identity.domain.SuperAdmin;
import zelisline.ub.identity.repository.SuperAdminRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SuperAdminGlobalCatalogIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SuperAdminRepository superAdminRepository;

    @Autowired
    private GlobalCatalogRepository globalCatalogRepository;

    @Autowired
    private GlobalProductRepository globalProductRepository;

    @Autowired
    private GlobalProductPackRepository globalProductPackRepository;

    @Autowired
    private GlobalProductPackItemRepository globalProductPackItemRepository;

    @Autowired
    private GlobalSupplierTemplateRepository globalSupplierTemplateRepository;

    @Autowired
    private GlobalProductSupplierLinkRepository globalProductSupplierLinkRepository;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private ItemTypeRepository itemTypeRepository;

    @Autowired
    private CatalogBootstrapService catalogBootstrapService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String saToken;
    private String productId;

    @BeforeEach
    void seed() throws Exception {
        itemRepository.deleteAll();
        globalProductSupplierLinkRepository.deleteAll();
        globalSupplierTemplateRepository.deleteAll();
        globalProductPackItemRepository.deleteAll();
        globalProductPackRepository.deleteAll();
        globalProductRepository.deleteAll();
        globalCatalogRepository.deleteAll();
        businessRepository.deleteAll();
        superAdminRepository.deleteAll();

        SuperAdmin admin = new SuperAdmin();
        admin.setEmail("ops-catalog@example.com");
        admin.setName("Ops Catalog");
        admin.setPasswordHash(passwordEncoder.encode("super-secret-pass"));
        admin.setActive(true);
        superAdminRepository.save(admin);

        GlobalCatalog catalog = new GlobalCatalog();
        catalog.setCode("default");
        catalog.setName("Kenya Retail Catalog");
        catalog.setRegionCode("KE");
        catalog.setCurrency("KES");
        catalog.setStatus("published");
        globalCatalogRepository.save(catalog);

        GlobalProduct product = new GlobalProduct();
        product.setCatalogId(catalog.getId());
        product.setName("Draft Juice 1L");
        product.setBarcode("9998887776665");
        product.setUnitType("each");
        product.setStatus(GlobalProductStatus.DRAFT);
        product.setSortOrder(0);
        globalProductRepository.save(product);
        productId = product.getId();

        String json = mockMvc.perform(post("/api/v1/super-admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ops-catalog@example.com","password":"super-secret-pass"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        saToken = JsonPath.read(json, "$.accessToken");
    }

    @Test
    void metaAndListIncludeDraftsForSuperAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/super-admin/global-catalog/meta")
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productCount").value(1))
                .andExpect(jsonPath("$.draftCount").value(1))
                .andExpect(jsonPath("$.missingImageCount").value(1));

        mockMvc.perform(get("/api/v1/super-admin/global-catalog/products")
                        .param("missingImage", "true")
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].status").value("draft"));
    }

    @Test
    void patchAndPublishProduct() throws Exception {
        String getJson = mockMvc.perform(get("/api/v1/super-admin/global-catalog/products/{id}", productId)
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Number version = JsonPath.read(getJson, "$.version");

        mockMvc.perform(patch("/api/v1/super-admin/global-catalog/products/{id}", productId)
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"version":%s,"name":"Draft Juice 1 Litre","status":"draft"}
                                """.formatted(version.longValue())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Draft Juice 1 Litre"));

        mockMvc.perform(post("/api/v1/super-admin/global-catalog/products/publish")
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"ids":["%s"]}
                                """.formatted(productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedCount").value(1));

        mockMvc.perform(get("/api/v1/super-admin/global-catalog/products/{id}", productId)
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("published"));
    }

    @Test
    void patchRejectsStaleVersion() throws Exception {
        mockMvc.perform(patch("/api/v1/super-admin/global-catalog/products/{id}", productId)
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"version":999,"name":"Nope"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void createProductDefaultsToDraft() throws Exception {
        mockMvc.perform(post("/api/v1/super-admin/global-catalog/products")
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"New Snack Bar","barcode":"1112223334445"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.name").value("New Snack Bar"));
    }

    @Test
    void rejectsDuplicateBarcodeOnCreate() throws Exception {
        mockMvc.perform(post("/api/v1/super-admin/global-catalog/products")
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"Clone Juice","barcode":"9998887776665"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void patchPackMembershipReplacesProductIds() throws Exception {
        var catalog = globalCatalogRepository.findByCode("default").orElseThrow();

        GlobalProductPack pack = new GlobalProductPack();
        pack.setCatalogId(catalog.getId());
        pack.setCode("test-pack");
        pack.setName("Test Pack");
        pack.setStatus("published");
        pack.setSortOrder(0);
        pack = globalProductPackRepository.save(pack);

        GlobalProduct second = new GlobalProduct();
        second.setCatalogId(catalog.getId());
        second.setName("Second Product");
        second.setBarcode("1110002223334");
        second.setUnitType("each");
        second.setStatus(GlobalProductStatus.PUBLISHED);
        second = globalProductRepository.save(second);

        mockMvc.perform(patch("/api/v1/super-admin/global-catalog/packs/{id}", pack.getId())
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"productIds":["%s","%s"],"storeKitId":"mini-mart"}
                                """.formatted(productId, second.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productIds.length()").value(2))
                .andExpect(jsonPath("$.storeKitId").value("mini-mart"));

        mockMvc.perform(get("/api/v1/super-admin/global-catalog/packs/{id}", pack.getId())
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productIds.length()").value(2))
                .andExpect(jsonPath("$.storeKitId").value("mini-mart"));
    }

    @Test
    void applyMarginFromBuyingUpdatesSellAndSuggestedPct() throws Exception {
        GlobalProduct product = globalProductRepository.findById(productId).orElseThrow();
        product.setRecommendedBuyingPrice(new java.math.BigDecimal("100.00"));
        product.setRecommendedSellingPrice(new java.math.BigDecimal("110.00"));
        globalProductRepository.save(product);

        mockMvc.perform(post("/api/v1/super-admin/global-catalog/products/apply-margin")
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"ids":["%s"],"marginPct":25,"mode":"fromBuying"}
                                """.formatted(productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(1))
                .andExpect(jsonPath("$.skippedCount").value(0));

        mockMvc.perform(get("/api/v1/super-admin/global-catalog/products/{id}", productId)
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendedBuyingPrice").value(100.0))
                .andExpect(jsonPath("$.recommendedSellingPrice").value(125.0))
                .andExpect(jsonPath("$.suggestedMarginPct").value(25.0));
    }

    @Test
    void exportAndImportCsvRoundTrip() throws Exception {
        mockMvc.perform(get("/api/v1/super-admin/global-catalog/products/export.csv")
                        .header("Authorization", "Bearer " + saToken)
                        .param("status", "draft"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/csv")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Draft Juice")));

        String csv = """
                name,brand,barcode,status,unit_type,recommended_selling_price
                Imported Chips,Local,5554443332221,draft,each,120.00
                """;
        mockMvc.perform(multipart("/api/v1/super-admin/global-catalog/products/import")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file",
                                "import.csv",
                                "text/csv",
                                csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdCount").value(1))
                .andExpect(jsonPath("$.updatedCount").value(0));

        assertTrue(globalProductRepository.findAll().stream()
                .anyMatch(p -> "Imported Chips".equals(p.getName())
                        && "5554443332221".equals(p.getBarcode())));
    }

    @Test
    void backfillAdoptedImagesSetsCoverWhenMediaUnavailable() throws Exception {
        GlobalProduct product = globalProductRepository.findById(productId).orElseThrow();
        product.setStatus(GlobalProductStatus.PUBLISHED);
        product.setImageUrl("https://res.cloudinary.com/demo/image/upload/sample.jpg");
        globalProductRepository.save(product);

        Business business = new Business();
        business.setName("Adopted Shop");
        business.setSlug("adopted-shop");
        business.setCountryCode("KE");
        business.setCurrency("KES");
        business = businessRepository.save(business);

        catalogBootstrapService.seedDefaultItemTypesIfMissing(business.getId());
        String goodsTypeId = itemTypeRepository.findByBusinessIdAndTypeKey(business.getId(), "goods")
                .orElseThrow()
                .getId();

        Item item = new Item();
        item.setBusinessId(business.getId());
        item.setSku("ADOPTED-1");
        item.setName("Adopted Juice");
        item.setItemTypeId(goodsTypeId);
        item.setUnitType("each");
        item.setGlobalProductSourceId(productId);
        item = itemRepository.save(item);

        mockMvc.perform(post("/api/v1/super-admin/global-catalog/products/{id}/backfill-images", productId)
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productsProcessed").value(1))
                .andExpect(jsonPath("$.itemsUpdated").value(1));

        Item refreshed = itemRepository.findById(item.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(refreshed.getImageKey())
                .isEqualTo("https://res.cloudinary.com/demo/image/upload/sample.jpg");
    }

    @Test
    void createSupplierTemplateAndAttachAsPrimary() throws Exception {
        String createJson = mockMvc.perform(post("/api/v1/super-admin/global-catalog/suppliers")
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"code":"bidco","name":"Bidco Africa","supplierType":"distributor"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("BIDCO"))
                .andExpect(jsonPath("$.tenantSupplierCodeHint").value("GC-BIDCO"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String templateId = JsonPath.read(createJson, "$.id");

        mockMvc.perform(put("/api/v1/super-admin/global-catalog/products/{id}/suppliers", productId)
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"globalSupplierTemplateId":"%s","primary":true,"defaultCostPrice":42.5,"supplierSku":"BID-JUICE"}
                                """.formatted(templateId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primary").value(true))
                .andExpect(jsonPath("$.supplierSku").value("BID-JUICE"));

        mockMvc.perform(get("/api/v1/super-admin/global-catalog/products/{id}/suppliers", productId)
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].templateCode").value("BIDCO"))
                .andExpect(jsonPath("$[0].primary").value(true));
    }

    @Test
    void listCatalogsIncludesDefaultAndUgRetail() throws Exception {
        GlobalCatalog ug = new GlobalCatalog();
        ug.setCode("ug-retail");
        ug.setName("Uganda Retail Catalog");
        ug.setRegionCode("UG");
        ug.setCurrency("UGX");
        ug.setStatus("published");
        globalCatalogRepository.save(ug);

        mockMvc.perform(get("/api/v1/super-admin/global-catalog/catalogs")
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.code=='default')].currency").value(org.hamcrest.Matchers.hasItem("KES")))
                .andExpect(jsonPath("$[?(@.code=='ug-retail')].regionCode").value(org.hamcrest.Matchers.hasItem("UG")));
    }

    @Test
    void createProductWithCatalogIdLandsOnUgCatalog() throws Exception {
        GlobalCatalog ug = new GlobalCatalog();
        ug.setCode("ug-retail");
        ug.setName("Uganda Retail Catalog");
        ug.setRegionCode("UG");
        ug.setCurrency("UGX");
        ug.setStatus("published");
        ug = globalCatalogRepository.save(ug);

        String createdJson = mockMvc.perform(post("/api/v1/super-admin/global-catalog/products")
                        .param("catalogId", ug.getId())
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"UG Matooke","barcode":"6001112223334"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("UG Matooke"))
                .andExpect(jsonPath("$.catalogId").value(ug.getId()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String createdId = JsonPath.read(createdJson, "$.id");

        mockMvc.perform(get("/api/v1/super-admin/global-catalog/products")
                        .param("catalogId", ug.getId())
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='%s')]".formatted(createdId)).exists());

        mockMvc.perform(get("/api/v1/super-admin/global-catalog/meta")
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogCode").value("default"))
                .andExpect(jsonPath("$.productCount").value(1));
    }
}
