package zelisline.ub.globalcatalog.api;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.finance.application.LedgerBootstrapService;
import zelisline.ub.finance.repository.LedgerAccountRepository;
import zelisline.ub.globalcatalog.domain.GlobalCatalog;
import zelisline.ub.globalcatalog.domain.GlobalCategory;
import zelisline.ub.globalcatalog.domain.GlobalProduct;
import zelisline.ub.globalcatalog.repository.GlobalCatalogRepository;
import zelisline.ub.globalcatalog.repository.GlobalCategoryRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductRepository;
import zelisline.ub.identity.domain.Permission;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.RolePermission;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

import java.math.BigDecimal;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class GlobalCatalogIT {

    private static final String TENANT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PERM_GLOBAL_READ = "11111111-0000-0000-0000-000000000400";
    private static final String PERM_GLOBAL_ADOPT = "11111111-0000-0000-0000-000000000401";
    private static final String ROLE_OWNER = "22222222-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @Autowired
    private ItemCatalogService itemCatalogService;

    @Autowired
    private ItemTypeRepository itemTypeRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private CatalogBootstrapService catalogBootstrapService;

    @Autowired
    private GlobalCatalogRepository globalCatalogRepository;

    @Autowired
    private GlobalCategoryRepository globalCategoryRepository;

    @Autowired
    private GlobalProductRepository globalProductRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private LedgerBootstrapService ledgerBootstrapService;

    private User ownerA;
    private String branchId;
    private String globalProductId;

    @BeforeEach
    void seed() {
        globalProductRepository.deleteAll();
        globalCategoryRepository.deleteAll();
        globalCatalogRepository.deleteAll();
        itemRepository.deleteAll();
        ledgerAccountRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT_A);
        b.setName("Shop A");
        b.setSlug("shop-a");
        b.setCountryCode("KE");
        b.setCurrency("KES");
        businessRepository.save(b);

        Branch branch = new Branch();
        branch.setBusinessId(TENANT_A);
        branch.setName("Main");
        branch.setActive(true);
        branchId = branchRepository.save(branch).getId();

        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT_A);
        ledgerBootstrapService.ensureStandardAccounts(TENANT_A);

        permissionRepository.save(perm(PERM_GLOBAL_READ, "catalog.global.read", "Browse global catalog"));
        permissionRepository.save(perm(PERM_GLOBAL_ADOPT, "catalog.global.adopt", "Adopt global products"));

        Role ownerRole = new Role();
        ownerRole.setId(ROLE_OWNER);
        ownerRole.setRoleKey("owner");
        ownerRole.setName("Owner");
        ownerRole.setSystem(true);
        roleRepository.save(ownerRole);
        grant(ROLE_OWNER, PERM_GLOBAL_READ);
        grant(ROLE_OWNER, PERM_GLOBAL_ADOPT);

        ownerA = new User();
        ownerA.setBusinessId(TENANT_A);
        ownerA.setEmail("owner-a@test");
        ownerA.setName("Owner A");
        ownerA.setRoleId(ROLE_OWNER);
        ownerA.setStatus(UserStatus.ACTIVE);
        ownerA.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(ownerA);

        GlobalCatalog catalog = new GlobalCatalog();
        catalog.setCode("default");
        catalog.setName("Default");
        catalog.setRegionCode("KE");
        catalog.setCurrency("KES");
        catalog.setStatus("published");
        globalCatalogRepository.save(catalog);

        GlobalCategory category = new GlobalCategory();
        category.setCatalogId(catalog.getId());
        category.setName("Beverages");
        category.setSlug("beverages");
        category.setPosition(0);
        category.setTenantCategorySlugHint("beverages");
        globalCategoryRepository.save(category);

        GlobalProduct product = new GlobalProduct();
        product.setCatalogId(catalog.getId());
        product.setGlobalCategoryId(category.getId());
        product.setName("Test Cola 500ml");
        product.setBrand("Test Cola");
        product.setSize("500ml");
        product.setBarcode("1234567890123");
        product.setUnitType("each");
        product.setRecommendedBuyingPrice(new BigDecimal("65.00"));
        product.setRecommendedSellingPrice(new BigDecimal("80.00"));
        product.setStatus("published");
        product.setSortOrder(0);
        product.setSkuTemplate("TEST-COLA-SKU");
        globalProductRepository.save(product);
        globalProductId = product.getId();
    }

    @Test
    void getMetaReturnsCatalogCategoriesAndPacks() throws Exception {
        mockMvc.perform(get("/api/v1/global-catalog/meta")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogName").value("Default"))
                .andExpect(jsonPath("$.categories.length()").value(1))
                .andExpect(jsonPath("$.categories[0].name").value("Beverages"));
    }

    @Test
    void listProductsReturnsPublishedProducts() throws Exception {
        mockMvc.perform(get("/api/v1/global-catalog/products")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Test Cola 500ml"))
                .andExpect(jsonPath("$.content[0].alreadyImported").value(false));
    }

    @Test
    void adoptCreatesTenantItemAndLinksToGlobalProduct() throws Exception {
        String body = String.format(
                "{\"openingBranchId\":\"%s\",\"lines\":[{\"globalProductId\":\"%s\",\"sellingPrice\":90,\"buyingPrice\":70,\"openingQty\":10}]}",
                branchId, globalProductId
        );

        mockMvc.perform(post("/api/v1/global-catalog/adopt")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.importedCount").value(1))
                .andExpect(jsonPath("$.lines[0].status").value("imported"));

        var items = itemRepository.findByBusinessIdAndDeletedAtIsNull(TENANT_A);
        org.assertj.core.api.Assertions.assertThat(items).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(items.get(0).getGlobalProductSourceId()).isEqualTo(globalProductId);
    }

    @Test
    void listProductsOnlyNotImportedExcludesAdoptedProducts() throws Exception {
        String body = String.format(
                "{\"openingBranchId\":\"%s\",\"lines\":[{\"globalProductId\":\"%s\",\"sellingPrice\":90,\"buyingPrice\":70}]}",
                branchId, globalProductId
        );

        mockMvc.perform(post("/api/v1/global-catalog/adopt")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/global-catalog/products")
                        .param("onlyNotImported", "true")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        mockMvc.perform(get("/api/v1/global-catalog/products")
                        .param("onlyNotImported", "false")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].alreadyImported").value(true));
    }

    @Test
    void listProductsOnlyNotImportedExcludesMatchingBarcodeWithoutSourceLink() throws Exception {
        String goodsTypeId = itemTypeRepository.findByBusinessIdAndTypeKey(TENANT_A, "goods")
                .orElseThrow()
                .getId();

        itemCatalogService.createItem(
                TENANT_A,
                new CreateItemRequest(
                        "MANUAL-SKU-1",
                        "1234567890123",
                        "Different name",
                        null,
                        goodsTypeId,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                ),
                null
        );

        mockMvc.perform(get("/api/v1/global-catalog/products")
                        .param("onlyNotImported", "true")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        mockMvc.perform(get("/api/v1/global-catalog/products")
                        .param("onlyNotImported", "false")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].alreadyImported").value(true));
    }

    @Test
    void listProductsOnlyNotImportedExcludesMatchingNameWithoutSourceLink() throws Exception {
        String goodsTypeId = itemTypeRepository.findByBusinessIdAndTypeKey(TENANT_A, "goods")
                .orElseThrow()
                .getId();

        itemCatalogService.createItem(
                TENANT_A,
                new CreateItemRequest(
                        "MANUAL-SKU-2",
                        null,
                        "Test Cola 500ml",
                        null,
                        goodsTypeId,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                ),
                null
        );

        mockMvc.perform(get("/api/v1/global-catalog/products")
                        .param("onlyNotImported", "true")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void listProductsOnlyNotImportedExcludesMatchingCompactNameWithoutSourceLink() throws Exception {
        String goodsTypeId = itemTypeRepository.findByBusinessIdAndTypeKey(TENANT_A, "goods")
                .orElseThrow()
                .getId();

        itemCatalogService.createItem(
                TENANT_A,
                new CreateItemRequest(
                        "MANUAL-SKU-3",
                        null,
                        "TESTCOLA-500ML",
                        null,
                        goodsTypeId,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                ),
                null
        );

        mockMvc.perform(get("/api/v1/global-catalog/products")
                        .param("onlyNotImported", "true")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void listProductsOnlyNotImportedExcludesMatchingLegacyImportSourceId() throws Exception {
        String goodsTypeId = itemTypeRepository.findByBusinessIdAndTypeKey(TENANT_A, "goods")
                .orElseThrow()
                .getId();

        var created = itemCatalogService.createItem(
                TENANT_A,
                new CreateItemRequest(
                        "MANUAL-SKU-4",
                        "9999999999999",
                        "Unrelated product label",
                        null,
                        goodsTypeId,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                ),
                null
        );

        var item = itemRepository.findById(created.body().id()).orElseThrow();
        item.setLegacyImportSourceId(globalProductId);
        itemRepository.save(item);

        mockMvc.perform(get("/api/v1/global-catalog/products")
                        .param("onlyNotImported", "true")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void adoptSkipsSkuConflict() throws Exception {
        String goodsTypeId = itemTypeRepository.findByBusinessIdAndTypeKey(TENANT_A, "goods")
                .orElseThrow()
                .getId();

        itemCatalogService.createItem(
                TENANT_A,
                new CreateItemRequest(
                        "TAKEN-SKU-ONLY",
                        "9999999999991",
                        "Existing product",
                        null,
                        goodsTypeId,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                ),
                null
        );

        String body = String.format(
                "{\"openingBranchId\":\"%s\",\"lines\":[{\"globalProductId\":\"%s\",\"sku\":\"TAKEN-SKU-ONLY\",\"sellingPrice\":90,\"buyingPrice\":70}]}",
                branchId, globalProductId
        );

        mockMvc.perform(post("/api/v1/global-catalog/adopt/preview")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].status").value("skip_sku_conflict"))
                .andExpect(jsonPath("$.lines[0].itemId").isNotEmpty());

        mockMvc.perform(post("/api/v1/global-catalog/adopt")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.importedCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(1))
                .andExpect(jsonPath("$.lines[0].status").value("skip_sku_conflict"));
    }

    @Test
    void adoptMergeLinksExistingProductBySku() throws Exception {
        String goodsTypeId = itemTypeRepository.findByBusinessIdAndTypeKey(TENANT_A, "goods")
                .orElseThrow()
                .getId();

        var created = itemCatalogService.createItem(
                TENANT_A,
                new CreateItemRequest(
                        "MERGE-ME-SKU",
                        "9999999999992",
                        "Existing for merge",
                        null,
                        goodsTypeId,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                ),
                null
        );

        String body = String.format(
                "{\"openingBranchId\":\"%s\",\"lines\":[{\"globalProductId\":\"%s\",\"sku\":\"MERGE-ME-SKU\",\"onSkuConflict\":\"merge\",\"sellingPrice\":90,\"buyingPrice\":70}]}",
                branchId, globalProductId
        );

        mockMvc.perform(post("/api/v1/global-catalog/adopt")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.importedCount").value(1))
                .andExpect(jsonPath("$.lines[0].status").value("merged"))
                .andExpect(jsonPath("$.lines[0].itemId").value(created.body().id()));

        var item = itemRepository.findById(created.body().id()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(item.getGlobalProductSourceId()).isEqualTo(globalProductId);
    }

    @Test
    void adoptRenameAllocatesSuffixWhenSkuTaken() throws Exception {
        String goodsTypeId = itemTypeRepository.findByBusinessIdAndTypeKey(TENANT_A, "goods")
                .orElseThrow()
                .getId();

        itemCatalogService.createItem(
                TENANT_A,
                new CreateItemRequest(
                        "BASE-SKU",
                        "9999999999993",
                        "Blocks base sku",
                        null,
                        goodsTypeId,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                ),
                null
        );

        String body = String.format(
                "{\"openingBranchId\":\"%s\",\"lines\":[{\"globalProductId\":\"%s\",\"sku\":\"BASE-SKU\",\"onSkuConflict\":\"rename\",\"sellingPrice\":90,\"buyingPrice\":70}]}",
                branchId, globalProductId
        );

        mockMvc.perform(post("/api/v1/global-catalog/adopt")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.importedCount").value(1))
                .andExpect(jsonPath("$.lines[0].status").value("imported"))
                .andExpect(jsonPath("$.lines[0].sku").value("BASE-SKU-2"));
    }

    @Test
    void reAdoptSameGlobalProductIsSkipped() throws Exception {
        String body = String.format(
                "{\"openingBranchId\":\"%s\",\"lines\":[{\"globalProductId\":\"%s\",\"sellingPrice\":90,\"buyingPrice\":70}]}",
                branchId, globalProductId
        );

        mockMvc.perform(post("/api/v1/global-catalog/adopt")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/global-catalog/adopt")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.importedCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(1))
                .andExpect(jsonPath("$.lines[0].status").value("skip_already_imported"));
    }

    private Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }

    private void grant(String roleId, String permissionId) {
        RolePermission rp = new RolePermission();
        rp.setId(new RolePermission.Id(roleId, permissionId));
        rolePermissionRepository.save(rp);
    }
}
