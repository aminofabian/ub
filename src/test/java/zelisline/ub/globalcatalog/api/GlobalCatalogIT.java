package zelisline.ub.globalcatalog.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemImageRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.finance.application.LedgerBootstrapService;
import zelisline.ub.finance.repository.LedgerAccountRepository;
import zelisline.ub.globalcatalog.domain.GlobalCatalog;
import zelisline.ub.globalcatalog.domain.GlobalCategory;
import zelisline.ub.globalcatalog.domain.GlobalProduct;
import zelisline.ub.globalcatalog.domain.GlobalProductPack;
import zelisline.ub.globalcatalog.domain.GlobalProductPackItem;
import zelisline.ub.globalcatalog.domain.GlobalProductSupplierLink;
import zelisline.ub.globalcatalog.domain.GlobalSupplierTemplate;
import zelisline.ub.globalcatalog.repository.GlobalCatalogRepository;
import zelisline.ub.globalcatalog.repository.GlobalCategoryRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductPackItemRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductPackRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductSupplierLinkRepository;
import zelisline.ub.globalcatalog.repository.GlobalSupplierTemplateRepository;
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
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.suppliers.SupplierCodes;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

import java.math.BigDecimal;
import java.util.List;

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
    private CategoryRepository categoryRepository;

    @Autowired
    private ItemImageRepository itemImageRepository;

    @Autowired
    private CatalogBootstrapService catalogBootstrapService;

    @Autowired
    private GlobalCatalogRepository globalCatalogRepository;

    @Autowired
    private GlobalCategoryRepository globalCategoryRepository;

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
    private SupplierRepository supplierRepository;

    @Autowired
    private SupplierProductRepository supplierProductRepository;

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private LedgerBootstrapService ledgerBootstrapService;

    private User ownerA;
    private String branchId;
    private String globalProductId;

    @BeforeEach
    void seed() {
        globalProductSupplierLinkRepository.deleteAll();
        globalSupplierTemplateRepository.deleteAll();
        globalProductPackItemRepository.deleteAll();
        globalProductPackRepository.deleteAll();
        globalProductRepository.deleteAll();
        globalCategoryRepository.deleteAll();
        globalCatalogRepository.deleteAll();
        itemImageRepository.deleteAll();
        supplierProductRepository.deleteAll();
        supplierRepository.deleteAll();
        itemRepository.deleteAll();
        categoryRepository.deleteAll();
        saleRepository.deleteAll();
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
    void draftGlobalProductsHiddenFromTenantBrowse() throws Exception {
        GlobalProduct draft = globalProductRepository.findById(globalProductId).orElseThrow();
        draft.setStatus("draft");
        globalProductRepository.save(draft);

        mockMvc.perform(get("/api/v1/global-catalog/products")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void getMetaReturnsCatalogCategoriesAndPacks() throws Exception {
        mockMvc.perform(get("/api/v1/global-catalog/meta")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogName").value("Default"))
                .andExpect(jsonPath("$.catalogCode").value("default"))
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
    void adoptCreateMissingCategoriesCreatesTenantCategoryFromHint() throws Exception {
        org.assertj.core.api.Assertions.assertThat(
                categoryRepository.findByBusinessIdAndSlugAndActiveTrue(TENANT_A, "beverages")
        ).isEmpty();

        String body = String.format(
                "{\"openingBranchId\":\"%s\",\"createMissingCategories\":true,\"lines\":[{\"globalProductId\":\"%s\",\"sellingPrice\":90,\"buyingPrice\":70}]}",
                branchId, globalProductId
        );

        mockMvc.perform(post("/api/v1/global-catalog/adopt")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.importedCount").value(1));

        var category = categoryRepository.findByBusinessIdAndSlugAndActiveTrue(TENANT_A, "beverages")
                .orElseThrow();
        org.assertj.core.api.Assertions.assertThat(category.getName()).isEqualTo("Beverages");
        var item = itemRepository.findByBusinessIdAndDeletedAtIsNull(TENANT_A).get(0);
        org.assertj.core.api.Assertions.assertThat(item.getCategoryId()).isEqualTo(category.getId());
    }

    @Test
    void adoptWithoutCreateMissingCategoriesLeavesCategoryNull() throws Exception {
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

        org.assertj.core.api.Assertions.assertThat(
                categoryRepository.findByBusinessIdAndSlugAndActiveTrue(TENANT_A, "beverages")
        ).isEmpty();
        var item = itemRepository.findByBusinessIdAndDeletedAtIsNull(TENANT_A).get(0);
        org.assertj.core.api.Assertions.assertThat(item.getCategoryId()).isNull();
    }

    @Test
    void refreshFromTemplateUpdatesSellWhenFlagsEnabled() throws Exception {
        String adoptBody = String.format(
                "{\"openingBranchId\":\"%s\",\"lines\":[{\"globalProductId\":\"%s\",\"sellingPrice\":90,\"buyingPrice\":70}]}",
                branchId, globalProductId
        );
        mockMvc.perform(post("/api/v1/global-catalog/adopt")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content(adoptBody))
                .andExpect(status().isCreated());

        GlobalProduct product = globalProductRepository.findById(globalProductId).orElseThrow();
        product.setRecommendedSellingPrice(new BigDecimal("110.00"));
        product.setRecommendedBuyingPrice(new BigDecimal("75.00"));
        globalProductRepository.save(product);

        String noopBody = String.format(
                "{\"branchId\":\"%s\",\"globalProductIds\":[\"%s\"]}",
                branchId, globalProductId
        );
        mockMvc.perform(post("/api/v1/global-catalog/refresh")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content(noopBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(0))
                .andExpect(jsonPath("$.lines[0].status").value("skipped"));

        String previewBody = String.format(
                "{\"branchId\":\"%s\",\"globalProductIds\":[\"%s\"],\"refreshSellingPrice\":true,\"refreshBuyingPrice\":true}",
                branchId, globalProductId
        );
        mockMvc.perform(post("/api/v1/global-catalog/refresh/preview")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content(previewBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(1))
                .andExpect(jsonPath("$.lines[0].status").value("would_update"))
                .andExpect(jsonPath("$.lines[0].sellingUpdated").value(true));

        mockMvc.perform(post("/api/v1/global-catalog/refresh")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content(previewBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(1))
                .andExpect(jsonPath("$.lines[0].status").value("updated"))
                .andExpect(jsonPath("$.lines[0].sellingUpdated").value(true))
                .andExpect(jsonPath("$.lines[0].buyingUpdated").value(true));

        var item = itemRepository.findByBusinessIdAndDeletedAtIsNull(TENANT_A).get(0);
        org.assertj.core.api.Assertions.assertThat(item.getBuyingPrice()).isEqualByComparingTo("75.00");

        String skipCustomBody = String.format(
                "{\"branchId\":\"%s\",\"globalProductIds\":[\"%s\"],\"refreshSellingPrice\":true,\"skipCustomizedSellingPrice\":true}",
                branchId, globalProductId
        );
        GlobalProduct refreshedProduct = globalProductRepository.findById(globalProductId).orElseThrow();
        refreshedProduct.setRecommendedSellingPrice(new BigDecimal("130.00"));
        globalProductRepository.save(refreshedProduct);

        mockMvc.perform(post("/api/v1/global-catalog/refresh")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content(skipCustomBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(0))
                .andExpect(jsonPath("$.lines[0].message").value(org.hamcrest.Matchers.containsString("customized")));
    }

    @Test
    void adoptWithPrimarySupplierTemplateCreatesGcTenantSupplier() throws Exception {
        GlobalProduct product = globalProductRepository.findById(globalProductId).orElseThrow();
        GlobalSupplierTemplate template = new GlobalSupplierTemplate();
        template.setCatalogId(product.getCatalogId());
        template.setCode("BIDCO");
        template.setName("Bidco Africa");
        template.setSupplierType("distributor");
        globalSupplierTemplateRepository.save(template);

        GlobalProductSupplierLink link = new GlobalProductSupplierLink();
        link.setGlobalProductId(product.getId());
        link.setGlobalSupplierTemplateId(template.getId());
        link.setPrimary(true);
        link.setDefaultCostPrice(new BigDecimal("55.00"));
        link.setSupplierSku("BID-COLA");
        globalProductSupplierLinkRepository.save(link);

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
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.importedCount").value(1));

        var item = itemRepository.findByBusinessIdAndDeletedAtIsNull(TENANT_A).get(0);
        Supplier gc = supplierRepository.findByBusinessIdAndCodeAndDeletedAtIsNull(TENANT_A, "GC-BIDCO")
                .orElseThrow();
        org.assertj.core.api.Assertions.assertThat(gc.getName()).isEqualTo("Bidco Africa");

        List<SupplierProduct> links = supplierProductRepository.listForItem(TENANT_A, item.getId());
        SupplierProduct primary = links.stream()
                .filter(SupplierProduct::isActive)
                .filter(SupplierProduct::isPrimaryLink)
                .findFirst()
                .orElseThrow();
        org.assertj.core.api.Assertions.assertThat(primary.getSupplierId()).isEqualTo(gc.getId());
        org.assertj.core.api.Assertions.assertThat(primary.getSupplierSku()).isEqualTo("BID-COLA");
        org.assertj.core.api.Assertions.assertThat(primary.getDefaultCostPrice())
                .isEqualByComparingTo("55.00");

        boolean sysStillPrimary = links.stream().anyMatch(sp -> {
            if (!sp.isActive() || !sp.isPrimaryLink()) {
                return false;
            }
            return supplierRepository.findById(sp.getSupplierId())
                    .map(s -> SupplierCodes.SYSTEM_UNASSIGNED.equals(s.getCode()))
                    .orElse(false);
        });
        org.assertj.core.api.Assertions.assertThat(sysStillPrimary).isFalse();
    }

    @Test
    void adoptWithoutSupplierTemplateKeepsSysUnassigned() throws Exception {
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

        var item = itemRepository.findByBusinessIdAndDeletedAtIsNull(TENANT_A).get(0);
        List<SupplierProduct> links = supplierProductRepository.listForItem(TENANT_A, item.getId());
        SupplierProduct primary = links.stream()
                .filter(SupplierProduct::isActive)
                .filter(SupplierProduct::isPrimaryLink)
                .findFirst()
                .orElseThrow();
        Supplier supplier = supplierRepository.findById(primary.getSupplierId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(supplier.getCode()).isEqualTo(SupplierCodes.SYSTEM_UNASSIGNED);
    }

    @Test
    void adoptWithHttpsImageSetsCoverAndWarnsWhenMediaStoreUnavailable() throws Exception {
        GlobalProduct product = globalProductRepository.findById(globalProductId).orElseThrow();
        product.setImageUrl("https://res.cloudinary.com/demo/image/upload/sample.jpg");
        globalProductRepository.save(product);

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
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.importedCount").value(1))
                .andExpect(jsonPath("$.lines[0].status").value("imported"))
                .andExpect(jsonPath("$.lines[0].message").value(org.hamcrest.Matchers.containsString("gallery registration skipped")));

        var item = itemRepository.findByBusinessIdAndDeletedAtIsNull(TENANT_A).get(0);
        org.assertj.core.api.Assertions.assertThat(item.getImageKey())
                .isEqualTo("https://res.cloudinary.com/demo/image/upload/sample.jpg");
        org.assertj.core.api.Assertions.assertThat(
                itemImageRepository.findByItemIdOrderBySortOrderAscIdAsc(item.getId())
        ).isEmpty();
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

    @Test
    void replaceCatalogSoftDeletesThenAdoptsPack() throws Exception {
        var catalog = globalCatalogRepository.findByCode("default").orElseThrow();

        GlobalProductPack pack = new GlobalProductPack();
        pack.setCatalogId(catalog.getId());
        pack.setCode("replace-pack");
        pack.setName("Replace Pack");
        pack.setStatus("published");
        pack.setSortOrder(0);
        pack = globalProductPackRepository.save(pack);

        GlobalProductPackItem member = new GlobalProductPackItem();
        member.setPackId(pack.getId());
        member.setGlobalProductId(globalProductId);
        member.setSortOrder(0);
        globalProductPackItemRepository.save(member);

        String goodsTypeId = itemTypeRepository.findByBusinessIdAndTypeKey(TENANT_A, "goods")
                .orElseThrow()
                .getId();
        itemCatalogService.createItem(
                TENANT_A,
                new CreateItemRequest(
                        "TEST-COLA-SKU",
                        null,
                        "Old Cola",
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
                        null,
                        null
                ),
                ownerA.getId());

        mockMvc.perform(get("/api/v1/global-catalog/replace/preview")
                        .param("packId", pack.getId())
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.activeItemCount").value(1))
                .andExpect(jsonPath("$.packProductCount").value(1));

        mockMvc.perform(post("/api/v1/global-catalog/replace")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"openingBranchId":"%s","packId":"%s"}
                                """.formatted(branchId, pack.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.softDeletedCount").value(1))
                .andExpect(jsonPath("$.adopt.importedCount").value(1));

        var active = itemRepository.findByBusinessIdAndDeletedAtIsNull(TENANT_A);
        assertEquals(1, active.size());
        assertEquals("TEST-COLA-SKU", active.get(0).getSku());
        assertEquals(globalProductId, active.get(0).getGlobalProductSourceId());
        assertTrue(itemRepository.findAll().stream()
                .anyMatch(i -> i.getDeletedAt() != null && i.getSku().startsWith("del-")));
    }

    @Test
    void replaceCatalogBlockedWhenSalesExist() throws Exception {
        var catalog = globalCatalogRepository.findByCode("default").orElseThrow();
        GlobalProductPack pack = new GlobalProductPack();
        pack.setCatalogId(catalog.getId());
        pack.setCode("sales-block-pack");
        pack.setName("Sales Block Pack");
        pack.setStatus("published");
        pack.setSortOrder(0);
        pack = globalProductPackRepository.save(pack);

        Sale sale = new Sale();
        sale.setBusinessId(TENANT_A);
        sale.setBranchId(branchId);
        sale.setShiftId("shift-replace");
        sale.setStatus("completed");
        sale.setIdempotencyKey("idem-replace-block");
        sale.setGrandTotal(new BigDecimal("10.00"));
        sale.setSoldBy(ownerA.getId());
        sale.setSoldAt(java.time.Instant.now());
        saleRepository.save(sale);

        mockMvc.perform(post("/api/v1/global-catalog/replace")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"openingBranchId":"%s","packId":"%s"}
                                """.formatted(branchId, pack.getId())))
                .andExpect(status().isConflict());
    }

    @Test
    void globalCatalogCodeOverrideSelectsPublishedCatalog() throws Exception {
        GlobalCatalog alt = new GlobalCatalog();
        alt.setCode("ug-retail");
        alt.setName("Uganda Retail");
        alt.setRegionCode("UG");
        alt.setCurrency("UGX");
        alt.setStatus("published");
        globalCatalogRepository.save(alt);

        Business business = businessRepository.findById(TENANT_A).orElseThrow();
        business.setSettings("{\"globalCatalogCode\":\"ug-retail\"}");
        businessRepository.saveAndFlush(business);

        mockMvc.perform(get("/api/v1/global-catalog/meta")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogCode").value("ug-retail"))
                .andExpect(jsonPath("$.catalogName").value("Uganda Retail"))
                .andExpect(jsonPath("$.currency").value("UGX"));
    }

    @Test
    void countryCodeUgResolvesToUgRetailCatalog() throws Exception {
        GlobalCatalog ug = new GlobalCatalog();
        ug.setCode("ug-retail");
        ug.setName("Uganda Retail Catalog");
        ug.setRegionCode("UG");
        ug.setCurrency("UGX");
        ug.setStatus("published");
        globalCatalogRepository.save(ug);

        Business business = businessRepository.findById(TENANT_A).orElseThrow();
        business.setCountryCode("UG");
        business.setCurrency("UGX");
        businessRepository.saveAndFlush(business);

        mockMvc.perform(get("/api/v1/global-catalog/meta")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogCode").value("ug-retail"))
                .andExpect(jsonPath("$.currency").value("UGX"))
                .andExpect(jsonPath("$.categories.length()").value(0));
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
