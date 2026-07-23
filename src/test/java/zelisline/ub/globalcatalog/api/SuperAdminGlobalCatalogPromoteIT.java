package zelisline.ub.globalcatalog.api;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

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
import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.domain.ItemImage;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemImageRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.globalcatalog.application.GlobalCatalogJobRunner;
import zelisline.ub.globalcatalog.domain.GlobalCatalog;
import zelisline.ub.globalcatalog.domain.GlobalCategory;
import zelisline.ub.globalcatalog.domain.GlobalProduct;
import zelisline.ub.globalcatalog.domain.GlobalProductStatus;
import zelisline.ub.globalcatalog.repository.GlobalCatalogJobRepository;
import zelisline.ub.globalcatalog.repository.GlobalCatalogRepository;
import zelisline.ub.globalcatalog.repository.GlobalCategoryRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductImageRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductRepository;
import zelisline.ub.identity.domain.SuperAdmin;
import zelisline.ub.identity.repository.SuperAdminRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SuperAdminGlobalCatalogPromoteIT {

    private static final String SOURCE_BUSINESS = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SuperAdminRepository superAdminRepository;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private GlobalCatalogRepository globalCatalogRepository;

    @Autowired
    private GlobalProductRepository globalProductRepository;

    @Autowired
    private GlobalCategoryRepository globalCategoryRepository;

    @Autowired
    private GlobalProductImageRepository globalProductImageRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ItemImageRepository itemImageRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private ItemTypeRepository itemTypeRepository;

    @Autowired
    private CatalogBootstrapService catalogBootstrapService;

    @Autowired
    private GlobalCatalogJobRepository globalCatalogJobRepository;

    @Autowired
    private GlobalCatalogJobRunner globalCatalogJobRunner;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String saToken;
    private String sourceItemId;
    private String catalogId;

    @BeforeEach
    void seed() throws Exception {
        itemRepository.deleteAll();
        itemImageRepository.deleteAll();
        categoryRepository.deleteAll();
        globalCatalogJobRepository.deleteAll();
        globalProductImageRepository.deleteAll();
        globalProductRepository.deleteAll();
        globalCategoryRepository.deleteAll();
        globalCatalogRepository.deleteAll();
        businessRepository.deleteAll();
        superAdminRepository.deleteAll();

        SuperAdmin admin = new SuperAdmin();
        admin.setEmail("ops-promote@example.com");
        admin.setName("Ops Promote");
        admin.setPasswordHash(passwordEncoder.encode("super-secret-pass"));
        admin.setActive(true);
        superAdminRepository.save(admin);

        Business business = new Business();
        business.setId(SOURCE_BUSINESS);
        business.setName("Palmart Flagship");
        business.setSlug("palmart");
        business.setCountryCode("KE");
        business.setCurrency("KES");
        businessRepository.save(business);

        catalogBootstrapService.seedDefaultItemTypesIfMissing(SOURCE_BUSINESS);
        String goodsTypeId = itemTypeRepository.findByBusinessIdAndTypeKey(SOURCE_BUSINESS, "goods")
                .orElseThrow()
                .getId();

        GlobalCatalog catalog = new GlobalCatalog();
        catalog.setCode("default");
        catalog.setName("Kenya Retail Catalog");
        catalog.setRegionCode("KE");
        catalog.setCurrency("KES");
        catalog.setStatus("published");
        globalCatalogRepository.save(catalog);
        catalogId = catalog.getId();

        Item item = new Item();
        item.setBusinessId(SOURCE_BUSINESS);
        item.setSku("PROMOTE-COLA");
        item.setName("Promote Cola 500ml");
        item.setBrand("Promote Cola");
        item.setSize("500ml");
        item.setBarcode("5554443332221");
        item.setItemTypeId(goodsTypeId);
        item.setUnitType("each");
        item.setBuyingPrice(new BigDecimal("50.00"));
        item.setImageKey("https://res.cloudinary.com/demo/image/upload/sample.jpg");
        itemRepository.save(item);
        sourceItemId = item.getId();

        String json = mockMvc.perform(post("/api/v1/super-admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ops-promote@example.com","password":"super-secret-pass"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        saToken = JsonPath.read(json, "$.accessToken");
    }

    @Test
    void listsPreferredSourceBusinessFirst() throws Exception {
        mockMvc.perform(get("/api/v1/super-admin/global-catalog/source-businesses")
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].preferred").value(true))
                .andExpect(jsonPath("$[0].slug").value("palmart"));
    }

    @Test
    void previewThenPromoteCreatesDraftAndRePromoteUpdates() throws Exception {
        String body = """
                {"sourceBusinessId":"%s","itemIds":["%s"],"onConflict":"update","publish":false}
                """.formatted(SOURCE_BUSINESS, sourceItemId);

        mockMvc.perform(post("/api/v1/super-admin/global-catalog/promote/preview")
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdCount").value(1))
                .andExpect(jsonPath("$.updatedCount").value(0))
                .andExpect(jsonPath("$.imageRehostCount").value(1));

        org.assertj.core.api.Assertions.assertThat(globalProductRepository.findAll()).isEmpty();

        String commit = mockMvc.perform(post("/api/v1/super-admin/global-catalog/promote")
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdCount").value(1))
                .andExpect(jsonPath("$.lines[0].action").value("created"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String globalId = JsonPath.read(commit, "$.lines[0].globalProductId");
        GlobalProduct created = globalProductRepository.findById(globalId).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(created.getStatus()).isEqualTo(GlobalProductStatus.DRAFT);
        org.assertj.core.api.Assertions.assertThat(created.getBarcode()).isEqualTo("5554443332221");
        org.assertj.core.api.Assertions.assertThat(created.getImageUrl())
                .isEqualTo("https://res.cloudinary.com/demo/image/upload/sample.jpg");
        org.assertj.core.api.Assertions.assertThat(created.getCatalogId()).isEqualTo(catalogId);

        Item renamed = itemRepository.findById(sourceItemId).orElseThrow();
        renamed.setName("Promote Cola 500ml Fresh");
        itemRepository.save(renamed);

        mockMvc.perform(post("/api/v1/super-admin/global-catalog/promote")
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdCount").value(0))
                .andExpect(jsonPath("$.updatedCount").value(1))
                .andExpect(jsonPath("$.lines[0].globalProductId").value(globalId));

        org.assertj.core.api.Assertions.assertThat(globalProductRepository.findAll()).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(globalProductRepository.findById(globalId).orElseThrow().getName())
                .isEqualTo("Promote Cola 500ml Fresh");
    }

    @Test
    void onConflictSkipDoesNotUpdate() throws Exception {
        GlobalProduct existing = new GlobalProduct();
        existing.setCatalogId(catalogId);
        existing.setName("Existing");
        existing.setBarcode("5554443332221");
        existing.setUnitType("each");
        existing.setStatus(GlobalProductStatus.DRAFT);
        globalProductRepository.save(existing);

        String body = """
                {"sourceBusinessId":"%s","itemIds":["%s"],"onConflict":"skip"}
                """.formatted(SOURCE_BUSINESS, sourceItemId);

        mockMvc.perform(post("/api/v1/super-admin/global-catalog/promote")
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skippedCount").value(1))
                .andExpect(jsonPath("$.createdCount").value(0))
                .andExpect(jsonPath("$.updatedCount").value(0));
    }

    @Test
    void promoteJobEnqueuesAndCompletesViaRunner() throws Exception {
        String body = """
                {"sourceBusinessId":"%s","itemIds":["%s"],"onConflict":"update","publish":false}
                """.formatted(SOURCE_BUSINESS, sourceItemId);

        String createJson = mockMvc.perform(post("/api/v1/super-admin/global-catalog/promote/jobs")
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String jobId = JsonPath.read(createJson, "$.jobId");

        mockMvc.perform(get("/api/v1/super-admin/global-catalog/promote/jobs/{jobId}", jobId)
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"));

        globalCatalogJobRunner.processNext();

        mockMvc.perform(get("/api/v1/super-admin/global-catalog/promote/jobs/{jobId}", jobId)
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.rowsCommitted").value(1))
                .andExpect(jsonPath("$.result.createdCount").value(1));

        org.assertj.core.api.Assertions.assertThat(globalProductRepository.findAll()).hasSize(1);
    }

    @Test
    void promotePreservesCategoryParentsAndRepairsStaleLinks() throws Exception {
        Category parent = new Category();
        parent.setBusinessId(SOURCE_BUSINESS);
        parent.setName("Personal Care");
        parent.setSlug("personal-care");
        parent.setPosition(1);
        parent.setActive(true);
        parent = categoryRepository.save(parent);

        Category child = new Category();
        child.setBusinessId(SOURCE_BUSINESS);
        child.setName("Bath Soap");
        child.setSlug("bath-soap");
        child.setParentId(parent.getId());
        child.setPosition(2);
        child.setActive(true);
        child = categoryRepository.save(child);

        // Empty sibling branch should also appear after promote.
        Category emptyBranch = new Category();
        emptyBranch.setBusinessId(SOURCE_BUSINESS);
        emptyBranch.setName("Oral Care");
        emptyBranch.setSlug("oral-care");
        emptyBranch.setParentId(parent.getId());
        emptyBranch.setPosition(3);
        emptyBranch.setActive(true);
        categoryRepository.save(emptyBranch);

        // Pre-existing flat category with wrong name/parent — promote must repair it.
        GlobalCategory staleChild = new GlobalCategory();
        staleChild.setCatalogId(catalogId);
        staleChild.setName("Old Bath Soap");
        staleChild.setSlug("bath-soap");
        staleChild.setPosition(99);
        staleChild.setActive(true);
        globalCategoryRepository.save(staleChild);

        Item item = itemRepository.findById(sourceItemId).orElseThrow();
        item.setCategoryId(child.getId());
        itemRepository.save(item);

        String body = """
                {"sourceBusinessId":"%s","itemIds":["%s"],"onConflict":"update","publish":false}
                """.formatted(SOURCE_BUSINESS, sourceItemId);

        mockMvc.perform(post("/api/v1/super-admin/global-catalog/promote")
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdCount").value(1));

        GlobalCategory globalParent = globalCategoryRepository
                .findByCatalogIdAndSlug(catalogId, "personal-care")
                .orElseThrow();
        GlobalCategory globalChild = globalCategoryRepository
                .findByCatalogIdAndSlug(catalogId, "bath-soap")
                .orElseThrow();
        GlobalCategory globalEmpty = globalCategoryRepository
                .findByCatalogIdAndSlug(catalogId, "oral-care")
                .orElseThrow();

        org.assertj.core.api.Assertions.assertThat(globalParent.getParentId()).isNull();
        org.assertj.core.api.Assertions.assertThat(globalParent.getName()).isEqualTo("Personal Care");
        org.assertj.core.api.Assertions.assertThat(globalChild.getParentId()).isEqualTo(globalParent.getId());
        org.assertj.core.api.Assertions.assertThat(globalChild.getName()).isEqualTo("Bath Soap");
        org.assertj.core.api.Assertions.assertThat(globalChild.getPosition()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(globalEmpty.getParentId()).isEqualTo(globalParent.getId());

        GlobalProduct promoted = globalProductRepository.findAll().stream()
                .filter(p -> catalogId.equals(p.getCatalogId()))
                .findFirst()
                .orElseThrow();
        org.assertj.core.api.Assertions.assertThat(promoted.getGlobalCategoryId()).isEqualTo(globalChild.getId());
    }

    @Test
    void promoteCopiesFullImageGallery() throws Exception {
        Item item = itemRepository.findById(sourceItemId).orElseThrow();
        item.setImageKey("https://res.cloudinary.com/demo/image/upload/cover.jpg");
        itemRepository.save(item);

        ItemImage second = new ItemImage();
        second.setItemId(item.getId());
        second.setSecureUrl("https://res.cloudinary.com/demo/image/upload/gallery-2.jpg");
        second.setSortOrder(0);
        second.setProvider("cloudinary");
        second.setCloudinaryPublicId("demo/gallery-2");
        itemImageRepository.save(second);

        ItemImage third = new ItemImage();
        third.setItemId(item.getId());
        third.setSecureUrl("https://res.cloudinary.com/demo/image/upload/gallery-3.jpg");
        third.setSortOrder(1);
        third.setProvider("cloudinary");
        third.setCloudinaryPublicId("demo/gallery-3");
        itemImageRepository.save(third);

        String body = """
                {"sourceBusinessId":"%s","itemIds":["%s"],"onConflict":"update","publish":false}
                """.formatted(SOURCE_BUSINESS, sourceItemId);

        String commit = mockMvc.perform(post("/api/v1/super-admin/global-catalog/promote")
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdCount").value(1))
                .andExpect(jsonPath("$.imageRehostCount").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String globalId = JsonPath.read(commit, "$.lines[0].globalProductId");
        var gallery = globalProductImageRepository.findByGlobalProductIdOrderBySortOrderAscIdAsc(globalId);
        org.assertj.core.api.Assertions.assertThat(gallery).hasSize(3);
        org.assertj.core.api.Assertions.assertThat(gallery.get(0).getImageUrl())
                .isEqualTo("https://res.cloudinary.com/demo/image/upload/gallery-2.jpg");
        org.assertj.core.api.Assertions.assertThat(gallery.get(1).getImageUrl())
                .isEqualTo("https://res.cloudinary.com/demo/image/upload/gallery-3.jpg");
        org.assertj.core.api.Assertions.assertThat(gallery.get(2).getImageUrl())
                .isEqualTo("https://res.cloudinary.com/demo/image/upload/cover.jpg");

        GlobalProduct product = globalProductRepository.findById(globalId).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(product.getImageUrl())
                .isEqualTo(gallery.get(0).getImageUrl());
    }

    @Test
    void promoteCopiesPackageVariantMetadata() throws Exception {
        Item item = itemRepository.findById(sourceItemId).orElseThrow();
        item.setVariantName("Tray of 30");
        item.setPackageVariant(true);
        item.setPackagingUnitName("Tray");
        item.setPackagingUnitQty(new BigDecimal("30"));
        item.setStocked(false);
        item.setSize(null);
        itemRepository.save(item);

        String body = """
                {"sourceBusinessId":"%s","itemIds":["%s"],"onConflict":"update","publish":false}
                """.formatted(SOURCE_BUSINESS, sourceItemId);

        String commit = mockMvc.perform(post("/api/v1/super-admin/global-catalog/promote")
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdCount").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String globalId = JsonPath.read(commit, "$.lines[0].globalProductId");
        GlobalProduct product = globalProductRepository.findById(globalId).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(product.getVariantName()).isEqualTo("Tray of 30");
        org.assertj.core.api.Assertions.assertThat(product.isPackageVariant()).isTrue();
        org.assertj.core.api.Assertions.assertThat(product.getPackagingUnitName()).isEqualTo("Tray");
        org.assertj.core.api.Assertions.assertThat(product.getPackagingUnitQty())
                .isEqualByComparingTo("30");
        org.assertj.core.api.Assertions.assertThat(product.isStocked()).isFalse();
    }

    @Test
    void promoteWithCatalogIdTargetsUgCatalog() throws Exception {
        GlobalCatalog ug = new GlobalCatalog();
        ug.setCode("ug-retail");
        ug.setName("Uganda Retail Catalog");
        ug.setRegionCode("UG");
        ug.setCurrency("UGX");
        ug.setStatus("published");
        ug = globalCatalogRepository.save(ug);

        String body = """
                {"sourceBusinessId":"%s","itemIds":["%s"],"onConflict":"update","publish":false,"catalogId":"%s"}
                """.formatted(SOURCE_BUSINESS, sourceItemId, ug.getId());

        String commit = mockMvc.perform(post("/api/v1/super-admin/global-catalog/promote")
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdCount").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String globalId = JsonPath.read(commit, "$.lines[0].globalProductId");
        GlobalProduct created = globalProductRepository.findById(globalId).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(created.getCatalogId()).isEqualTo(ug.getId());
        org.assertj.core.api.Assertions.assertThat(
                globalProductRepository.findAll().stream().filter(p -> catalogId.equals(p.getCatalogId())).count()
        ).isZero();
    }

    @Test
    void draftRePromoteDoesNotDemotePublished() throws Exception {
        String publishBody = """
                {"sourceBusinessId":"%s","itemIds":["%s"],"onConflict":"update","publish":true}
                """.formatted(SOURCE_BUSINESS, sourceItemId);
        String commit = mockMvc.perform(post("/api/v1/super-admin/global-catalog/promote")
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content(publishBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdCount").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String globalId = JsonPath.read(commit, "$.lines[0].globalProductId");
        org.assertj.core.api.Assertions.assertThat(
                globalProductRepository.findById(globalId).orElseThrow().getStatus()
        ).isEqualTo(GlobalProductStatus.PUBLISHED);

        String draftBody = """
                {"sourceBusinessId":"%s","itemIds":["%s"],"onConflict":"update","publish":false}
                """.formatted(SOURCE_BUSINESS, sourceItemId);
        mockMvc.perform(post("/api/v1/super-admin/global-catalog/promote")
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content(draftBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(1));

        org.assertj.core.api.Assertions.assertThat(
                globalProductRepository.findById(globalId).orElseThrow().getStatus()
        ).isEqualTo(GlobalProductStatus.PUBLISHED);
    }

    @Test
    void archiveAllThenPromoteRevivesByBarcodeAsPublished() throws Exception {
        String first = """
                {"sourceBusinessId":"%s","itemIds":["%s"],"onConflict":"update","publish":true}
                """.formatted(SOURCE_BUSINESS, sourceItemId);
        String commit = mockMvc.perform(post("/api/v1/super-admin/global-catalog/promote")
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdCount").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String globalId = JsonPath.read(commit, "$.lines[0].globalProductId");

        mockMvc.perform(post("/api/v1/super-admin/global-catalog/products/archive-all")
                        .header("Authorization", "Bearer " + saToken)
                        .queryParam("catalogId", catalogId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedProductCount").value(1));

        org.assertj.core.api.Assertions.assertThat(
                globalProductRepository.findById(globalId).orElseThrow().getStatus()
        ).isEqualTo(GlobalProductStatus.ARCHIVED);

        // New tenant item id, same barcode — must revive the archived row, not skip/create ghost.
        Item clone = new Item();
        clone.setBusinessId(SOURCE_BUSINESS);
        clone.setSku("PROMOTE-COLA-2");
        clone.setName("Promote Cola 500ml");
        clone.setBrand("Promote Cola");
        clone.setSize("500ml");
        clone.setBarcode("5554443332221");
        clone.setItemTypeId(itemRepository.findById(sourceItemId).orElseThrow().getItemTypeId());
        clone.setUnitType("each");
        clone.setBuyingPrice(new BigDecimal("55.00"));
        clone = itemRepository.save(clone);

        String again = """
                {"sourceBusinessId":"%s","itemIds":["%s"],"onConflict":"update","publish":true,"catalogId":"%s"}
                """.formatted(SOURCE_BUSINESS, clone.getId(), catalogId);
        mockMvc.perform(post("/api/v1/super-admin/global-catalog/promote")
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content(again))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(1))
                .andExpect(jsonPath("$.createdCount").value(0));

        GlobalProduct revived = globalProductRepository.findById(globalId).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(revived.getStatus()).isEqualTo(GlobalProductStatus.PUBLISHED);
        org.assertj.core.api.Assertions.assertThat(revived.getRecommendedBuyingPrice())
                .isEqualByComparingTo("55.00");
    }
}
