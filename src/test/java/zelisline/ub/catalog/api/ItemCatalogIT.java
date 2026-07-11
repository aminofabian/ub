package zelisline.ub.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.api.dto.PatchItemRequest;
import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.IdempotencyKeyRepository;
import zelisline.ub.catalog.repository.ItemImageRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
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
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class ItemCatalogIT {

    private static final String TENANT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String TENANT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String PERM_READ = "11111111-0000-0000-0000-000000000040";
    private static final String PERM_WRITE = "11111111-0000-0000-0000-000000000041";
    private static final String ROLE_OWNER = "22222222-0000-0000-0000-000000000001";
    private static final String ROLE_BUTCHER_CASHIER = "22222222-0000-0000-0000-000000000009";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @Autowired
    private ItemTypeRepository itemTypeRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private ItemImageRepository itemImageRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private CatalogBootstrapService catalogBootstrapService;

    @Autowired
    private ItemCatalogService itemCatalogService;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private SupplierContactRepository supplierContactRepository;

    @Autowired
    private SupplierProductRepository supplierProductRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User ownerA;

    @BeforeEach
    void seed() {
        itemImageRepository.deleteAll();
        supplierProductRepository.deleteAll();
        supplierContactRepository.deleteAll();
        supplierRepository.deleteAll();
        itemRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        categoryRepository.deleteAll();
        itemTypeRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        businessRepository.deleteAll();

        insertBusiness(TENANT_A, "shop-a");
        insertBusiness(TENANT_B, "shop-b");
        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT_A);
        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT_B);

        permissionRepository.save(perm(PERM_READ, "catalog.items.read", "Read catalog"));
        permissionRepository.save(perm(PERM_WRITE, "catalog.items.write", "Write catalog"));

        Role ownerRole = new Role();
        ownerRole.setId(ROLE_OWNER);
        ownerRole.setBusinessId(null);
        ownerRole.setRoleKey("owner");
        ownerRole.setName("Owner");
        ownerRole.setSystem(true);
        roleRepository.save(ownerRole);
        grant(ROLE_OWNER, PERM_READ);
        grant(ROLE_OWNER, PERM_WRITE);

        ownerA = new User();
        ownerA.setBusinessId(TENANT_A);
        ownerA.setEmail("owner-a@test");
        ownerA.setName("Owner A");
        ownerA.setRoleId(ROLE_OWNER);
        ownerA.setStatus(UserStatus.ACTIVE);
        ownerA.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(ownerA);

        User ownerB = new User();
        ownerB.setBusinessId(TENANT_B);
        ownerB.setEmail("owner-b@test");
        ownerB.setName("Owner B");
        ownerB.setRoleId(ROLE_OWNER);
        ownerB.setStatus(UserStatus.ACTIVE);
        ownerB.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(ownerB);
    }

    @Test
    void bulkCreate1000ItemsInUnder5Seconds() {
        String gid = goodsTypeId(TENANT_A);
        long t0 = System.nanoTime();
        itemCatalogService.bulkCreateSimpleItemsForPerfTest(TENANT_A, gid, 1000);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(ms).isLessThan(5000);
        assertThat(itemRepository.count()).isEqualTo(1000);
    }

    @Test
    void nextSkuEndpointReflectsSequenceForDefaultPrefix() throws Exception {
        String gid = goodsTypeId(TENANT_A);
        mockMvc.perform(get("/api/v1/items/next-sku")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedSku").value("SKU-10001"));

        itemCatalogService.createItem(TENANT_A, minimalItem("SKU-10050", "Num", gid), null);

        mockMvc.perform(get("/api/v1/items/next-sku")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedSku").value("SKU-10051"));
    }

    @Test
    void nextSkuEndpointUsesCategorySlugPrefix() throws Exception {
        String gid = goodsTypeId(TENANT_A);
        Category cat = new Category();
        cat.setBusinessId(TENANT_A);
        cat.setName("Beverages");
        cat.setSlug("beverages");
        cat.setPosition(0);
        String catId = categoryRepository.save(cat).getId();

        mockMvc.perform(get("/api/v1/items/next-sku")
                        .param("categoryId", catId)
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedSku").value("BEVERA-10001"));

        itemCatalogService.createItem(
                TENANT_A,
                new CreateItemRequest(
                        "BEVERA-10010",
                        null,
                        "Cola",
                        null,
                        gid,
                        catId,
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
                        null, null, null, null, null),
                null);

        mockMvc.perform(get("/api/v1/items/next-sku")
                        .param("categoryId", catId)
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedSku").value("BEVERA-10011"));
    }

    @Test
    void createItemWithoutSkuAllocatesNumericSku() throws Exception {
        String gid = goodsTypeId(TENANT_A);
        mockMvc.perform(post("/api/v1/items")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Auto SKU Parent\",\"itemTypeId\":\"" + gid + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("SKU-10001"));
    }

    @Test
    void createVariantWithoutSkuAllocatesStructuredVariantSku() throws Exception {
        String gid = goodsTypeId(TENANT_A);
        String parent = createItemViaService(TENANT_A, gid, "SKU-P-AUTO", "Parent auto");
        mockMvc.perform(post("/api/v1/items/" + parent + "/variants")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("{\"variantName\":\"Size M\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("SKU-P-AUTO-SIZE-M"));
    }

    @Test
    void concurrentPatchesEventuallyProduceOptimisticConflict() throws Exception {
        String gid = goodsTypeId(TENANT_A);
        boolean ok = false;
        for (int attempt = 0; attempt < 40; attempt++) {
            String itemId = createItemViaService(TENANT_A, gid, "SKU-conc-" + attempt, "Conc");
            if (runConcurrentBarcodePatchesOnce(itemId)) {
                ok = true;
                break;
            }
        }
        assertThat(ok).as("expected one optimistic lock failure across concurrent patch attempts").isTrue();
    }

    @Test
    void searchDoesNotLeakOtherTenantItems() throws Exception {
        String gid = goodsTypeId(TENANT_A);
        itemCatalogService.createItem(
                TENANT_A,
                minimalItem("SKU-ISO", "UniqueMarkerXYZ", gid),
                null
        );
        mockMvc.perform(get("/api/v1/items")
                        .param("search", "UniqueMarkerXYZ")
                        .header("X-Tenant-Id", TENANT_B)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, userIdForTenant(TENANT_B))
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void searchRanksPartialTokensAndTypos() throws Exception {
        String gid = goodsTypeId(TENANT_A);
        createItemViaService(TENANT_A, gid, "SKU-SUPA-PLAIN", "Supa Loaf");
        createItemViaService(TENANT_A, gid, "SKU-SUPA-S", "Supa Loaf Small");
        createItemViaService(TENANT_A, gid, "SKU-SUPA-M", "Supa Loaf Medium");
        createItemViaService(TENANT_A, gid, "SKU-BREAD", "Bread Rolls");

        mockMvc.perform(get("/api/v1/items")
                        .param("catalogScope", "SKUS_ONLY")
                        .param("search", "supa s")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].name").value("Supa Loaf Small"));

        mockMvc.perform(get("/api/v1/items")
                        .param("catalogScope", "SKUS_ONLY")
                        .param("search", "supa m")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Supa Loaf Medium"));

        mockMvc.perform(get("/api/v1/items")
                        .param("catalogScope", "SKUS_ONLY")
                        .param("search", "suap")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.content[0].name").value(org.hamcrest.Matchers.containsString("Supa")));
    }

    @Test
    void listItemsCatalogScopeFiltersParentsAndVariants() throws Exception {
        String gid = goodsTypeId(TENANT_A);
        String parent = createItemViaService(TENANT_A, gid, "SKU-SCOPE-P", "Scope parent unique");
        createItemViaService(TENANT_A, gid, "SKU-SCOPE-S", "Scope standalone unique");
        mockMvc.perform(post("/api/v1/items/" + parent + "/variants")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-SCOPE-V\",\"variantName\":\"Opt\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/items")
                        .param("catalogScope", "PARENTS_ONLY")
                        .param("search", "Scope parent unique")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("SKU-SCOPE-P"));

        mockMvc.perform(get("/api/v1/items")
                        .param("catalogScope", "PARENTS_ONLY")
                        .param("search", "Scope standalone unique")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        mockMvc.perform(get("/api/v1/items")
                        .param("catalogScope", "VARIANTS_ONLY")
                        .param("search", "Scope parent unique")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("SKU-SCOPE-V"));
    }

    @Test
    void listItemsCatalogRowTypesFilterAndCounts() throws Exception {
        String gid = goodsTypeId(TENANT_A);
        String parent = createItemViaService(TENANT_A, gid, "SKU-ROWTYPE-P", "Row type parent marker");
        createItemViaService(TENANT_A, gid, "SKU-ROWTYPE-S", "Row type standalone marker");
        mockMvc.perform(post("/api/v1/items/" + parent + "/variants")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-ROWTYPE-V\",\"variantName\":\"Size M\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/items/row-type-counts")
                        .param("search", "Row type")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parents").value(1))
                .andExpect(jsonPath("$.variants").value(1))
                .andExpect(jsonPath("$.standalones").value(1))
                .andExpect(jsonPath("$.missingBarcode").value(3))
                .andExpect(jsonPath("$.inactive").value(0))
                .andExpect(jsonPath("$.missingPrice").exists())
                .andExpect(jsonPath("$.zeroStock").exists())
                .andExpect(jsonPath("$.lowStock").exists());

        String inactiveItem = createItemViaService(TENANT_A, gid, "SKU-ROWTYPE-INACT", "Row type inactive marker");
        mockMvc.perform(patch("/api/v1/items/" + inactiveItem)
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/items/row-type-counts")
                        .param("search", "Row type")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inactive").value(1));

        mockMvc.perform(get("/api/v1/items")
                        .param("catalogRowTypes", "STANDALONE")
                        .param("search", "Row type")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("SKU-ROWTYPE-S"));

        mockMvc.perform(get("/api/v1/items")
                        .param("catalogRowTypes", "PARENT")
                        .param("catalogRowTypes", "VARIANT")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void listItemsAllScopeGroupsVariantsUnderParent() throws Exception {
        String gid = goodsTypeId(TENANT_A);
        String parent = createItemViaService(TENANT_A, gid, "SKU-TREE-P", "Tree parent alpha");
        mockMvc.perform(post("/api/v1/items/" + parent + "/variants")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-TREE-V\",\"variantName\":\"Zebra option\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/items")
                        .param("catalogScope", "ALL")
                        .param("search", "Tree parent alpha")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("SKU-TREE-V"));
    }

    @Test
    void butcherCashier_canListSkusOnlyCatalog() throws Exception {
        Role butcherRole = new Role();
        butcherRole.setId(ROLE_BUTCHER_CASHIER);
        butcherRole.setBusinessId(null);
        butcherRole.setRoleKey("butcher_cashier");
        butcherRole.setName("Butcher Cashier");
        butcherRole.setSystem(true);
        roleRepository.save(butcherRole);
        grant(ROLE_BUTCHER_CASHIER, PERM_READ);

        User butcherCashier = new User();
        butcherCashier.setBusinessId(TENANT_A);
        butcherCashier.setEmail("butcher-cashier@test");
        butcherCashier.setName("Butcher Cashier");
        butcherCashier.setRoleId(ROLE_BUTCHER_CASHIER);
        butcherCashier.setStatus(UserStatus.ACTIVE);
        butcherCashier.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(butcherCashier);

        String gid = goodsTypeId(TENANT_A);
        String itemId = createItemViaService(TENANT_A, gid, "SKU-BUTCHER-CUT", "Ribeye");

        mockMvc.perform(get("/api/v1/items")
                        .param("catalogScope", "SKUS_ONLY")
                        .param("itemTypeId", gid)
                        .param("search", "Ribeye")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, butcherCashier.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_BUTCHER_CASHIER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(itemId))
                .andExpect(jsonPath("$.content[0].name").value("Ribeye"));
    }

    @Test
    void patchWebPublishedRoundTrips() throws Exception {
        String gid = goodsTypeId(TENANT_A);
        String itemId = createItemViaService(TENANT_A, gid, "SKU-WEB-PUB", "Web show");
        mockMvc.perform(patch("/api/v1/items/" + itemId)
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("{\"webPublished\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webPublished").value(true));
        mockMvc.perform(get("/api/v1/items/" + itemId)
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webPublished").value(true));
    }

    @Test
    void postItemsIdempotencyReplaysSameResponse() throws Exception {
        String gid = goodsTypeId(TENANT_A);
        String body = """
                {"sku":"SKU-IDEM","name":"Idem","itemTypeId":"%s"}
                """.formatted(gid).trim();
        String key = "550e8400-e29b-41d4-a716-446655440000";
        String first = mockMvc.perform(post("/api/v1/items")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        mockMvc.perform(post("/api/v1/items")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEqualTo(first));
    }

    @Test
    void postItemsIdempotencyKeyWithDifferentBodyReturns409() throws Exception {
        String gid = goodsTypeId(TENANT_A);
        String key = "650e8400-e29b-41d4-a716-446655440001";
        mockMvc.perform(post("/api/v1/items")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-A\",\"name\":\"A\",\"itemTypeId\":\"" + gid + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/items")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-B\",\"name\":\"B\",\"itemTypeId\":\"" + gid + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void createItemWithEmptyStringSkuAllocatesNumericSku() throws Exception {
        String gid = goodsTypeId(TENANT_A);
        mockMvc.perform(post("/api/v1/items")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("{\"sku\":\"\",\"name\":\"Empty str SKU\",\"itemTypeId\":\"" + gid + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("SKU-10001"));
    }

    @Test
    void createVariantOfVariantReturns400() throws Exception {
        String gid = goodsTypeId(TENANT_A);
        String parent = createItemViaService(TENANT_A, gid, "SKU-P", "Parent");
        String variant = mockMvc.perform(post("/api/v1/items/" + parent + "/variants")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-V\",\"variantName\":\"Size\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String variantId = JsonPath.read(variant, "$.id");

        mockMvc.perform(post("/api/v1/items/" + variantId + "/variants")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-V2\",\"variantName\":\"Nope\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerItemImageStoresMetadataAndExposesGalleryOnGetItem() throws Exception {
        String gid = goodsTypeId(TENANT_A);
        String itemId = createItemViaService(TENANT_A, gid, "SKU-IMG", "Photo");
        String req = """
                {"s3Key":"tenant/a/items/one.jpg","width":800,"height":600,"contentType":"image/jpeg","altText":"Front","primary":true}
                """;
        String posted = mockMvc.perform(post("/api/v1/items/" + itemId + "/images")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content(req))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.s3Key").value("tenant/a/items/one.jpg"))
                .andExpect(jsonPath("$.provider").value("legacy"))
                .andExpect(jsonPath("$.sortOrder").value(0))
                .andExpect(jsonPath("$.width").value(800))
                .andExpect(jsonPath("$.height").value(600))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String imageId = JsonPath.read(posted, "$.id");

        mockMvc.perform(get("/api/v1/items/" + itemId)
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images.length()").value(1))
                .andExpect(jsonPath("$.images[0].id").value(imageId))
                .andExpect(jsonPath("$.imageKey").value("tenant/a/items/one.jpg"));
    }

    @Test
    void registerItemImageWithCloudinaryMetadataSetsCoverToSecureUrl() throws Exception {
        String gid = goodsTypeId(TENANT_A);
        String itemId = createItemViaService(TENANT_A, gid, "SKU-CLO", "Cloud");
        String req = """
                {"secureUrl":"https://res.cloudinary.com/demo/image/upload/v1/x","cloudinaryPublicId":"ub/demo/item/hero","width":100,"height":80,"primary":true}
                """;
        String posted = mockMvc.perform(post("/api/v1/items/" + itemId + "/images")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content(req))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.provider").value("cloudinary"))
                .andExpect(jsonPath("$.secureUrl").value("https://res.cloudinary.com/demo/image/upload/v1/x"))
                .andExpect(jsonPath("$.publicId").value("ub/demo/item/hero"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String imageId = JsonPath.read(posted, "$.id");

        mockMvc.perform(get("/api/v1/items/" + itemId)
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images[0].id").value(imageId))
                .andExpect(jsonPath("$.imageKey").value("https://res.cloudinary.com/demo/image/upload/v1/x"));
    }

    @Test
    void uploadItemImageReturns503WhenCloudinaryDisabled() throws Exception {
        String gid = goodsTypeId(TENANT_A);
        String itemId = createItemViaService(TENANT_A, gid, "SKU-UL", "Upload");
        mockMvc.perform(multipart("/api/v1/items/" + itemId + "/images/upload")
                        .file(new MockMultipartFile(
                                "file",
                                "x.png",
                                "image/png",
                                new byte[] {(byte) 0x89, 0x50}
                        ))
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void deleteItemImageClearsPrimaryImageKeyWhenItMatched() throws Exception {
        String gid = goodsTypeId(TENANT_A);
        String itemId = createItemViaService(TENANT_A, gid, "SKU-IMG2", "Photo2");
        String posted = mockMvc.perform(post("/api/v1/items/" + itemId + "/images")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("{\"s3Key\":\"k/main.png\",\"primary\":true}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String imageId = JsonPath.read(posted, "$.id");

        mockMvc.perform(delete("/api/v1/items/" + itemId + "/images/" + imageId)
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/items/" + itemId)
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images.length()").value(0))
                .andExpect(jsonPath("$.imageKey", nullValue()));
    }

    @Test
    void deleteSoftClearsBarcode() throws Exception {
        String gid = goodsTypeId(TENANT_A);
        String id = createItemViaService(TENANT_A, gid, "SKU-DEL", "Del", "111222333");
        mockMvc.perform(delete("/api/v1/items/" + id)
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isNoContent());

        assertThat(itemRepository.findById(id)).isPresent();
        assertThat(itemRepository.findById(id).orElseThrow().getBarcode()).isNull();
        assertThat(itemRepository.findById(id).orElseThrow().getDeletedAt()).isNotNull();
    }

    @Test
    void createWeighedItem_withKgUnit_succeedsAndReturnsWeightFields() throws Exception {
        String gid = goodsTypeId(TENANT_A);
        CreateItemRequest req = weighedItem("SKU-BEEF", "Beef Mince", gid, "kg");

        mockMvc.perform(post("/api/v1/items")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isWeighed").value(true))
                .andExpect(jsonPath("$.unitType").value("kg"))
                .andExpect(jsonPath("$.itemTypeId").value(gid));
    }

    @Test
    void createWeighedItem_withInvalidUnit_fails() throws Exception {
        String gid = goodsTypeId(TENANT_A);
        CreateItemRequest req = weighedItem("SKU-BAD", "Bad Mince", gid, "bottle");

        mockMvc.perform(post("/api/v1/items")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchItem_toWeighedWithInvalidUnit_fails() throws Exception {
        String gid = goodsTypeId(TENANT_A);
        String id = createItemViaService(TENANT_A, gid, "SKU-PATCH", "Patchable");

        PatchItemRequest patch = new PatchItemRequest(
                null, null, null, null, null, null, null, null,
                "box",
                true,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        mockMvc.perform(patch("/api/v1/items/" + id)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(patch))
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listItems_filteredByIsWeighed_returnsOnlyWeighedItems() throws Exception {
        String gid = goodsTypeId(TENANT_A);
        createItemViaService(TENANT_A, gid, "SKU-NORMAL", "Normal");
        CreateItemRequest weighedReq = weighedItem("SKU-WEIGHED", "Beef Mince", gid, "kg");
        itemCatalogService.createItem(TENANT_A, weighedReq, null);

        mockMvc.perform(get("/api/v1/items")
                        .param("isWeighed", "true")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].isWeighed").value(true))
                .andExpect(jsonPath("$.content[0].unitType").value("kg"))
                .andExpect(jsonPath("$.content[0].itemTypeId").value(gid));
    }

    private boolean runConcurrentBarcodePatchesOnce(String itemId) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger seq = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    itemCatalogService.patchItem(
                            TENANT_A,
                            itemId,
                            barcodeOnly("8999900" + seq.getAndIncrement())
                    );
                    successes.incrementAndGet();
                } catch (ObjectOptimisticLockingFailureException e) {
                    conflicts.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        Thread.sleep(50);
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(15, TimeUnit.SECONDS);
        return conflicts.get() == 1 && successes.get() == 1;
    }

    private String createItemViaService(String tenant, String gid, String sku, String name) {
        return itemCatalogService.createItem(tenant, minimalItem(sku, name, gid), null).body().id();
    }

    private String createItemViaService(String tenant, String gid, String sku, String name, String barcode) {
        CreateItemRequest req = new CreateItemRequest(
                sku,
                barcode,
                name,
                null,
                gid,
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
                null, null, null, null, null);
        return itemCatalogService.createItem(tenant, req, null).body().id();
    }

    private static PatchItemRequest barcodeOnly(String barcode) {
        return new PatchItemRequest(
                null,    // expectedUpdatedAt
                null,    // sku
                barcode, // barcode
                null,    // name
                null,    // description
                null,    // categoryId
                null,    // aisleId
                null,    // itemTypeId
                null,    // unitType
                null,    // isWeighed
                null,    // isSellable
                null,    // isStocked
                null,    // packageVariant
                null,    // packagingUnitName
                null,    // packagingUnitQty
                null,    // bundleQty
                null,    // bundlePrice
                null,    // buyingPrice
                null,    // bundleName
                null,    // minStockLevel
                null,    // reorderLevel
                null,    // reorderQty
                null,    // expiresAfterDays
                null,    // hasExpiry
                null,    // imageKey
                null,    // active
                null,    // webPublished
                null,    // brand
                null,    // size
                null,    // variantName
                null     // pluCode
        );
    }

    private static CreateItemRequest minimalItem(String sku, String name, String itemTypeId) {
        return new CreateItemRequest(
                sku,
                null,
                name,
                null,
                itemTypeId,
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
                null, null, null, null, null);
    }

    private static CreateItemRequest weighedItem(String sku, String name, String itemTypeId, String unitType) {
        return new CreateItemRequest(
                sku,
                null,
                name,
                null,
                itemTypeId,
                null,
                null,
                unitType,
                true,
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
                null, null);
    }

    private String goodsTypeId(String tenant) {
        return itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(tenant).stream()
                .filter(t -> "goods".equals(t.getTypeKey()))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private String userIdForTenant(String tenant) {
        return userRepository.findAll().stream()
                .filter(u -> tenant.equals(u.getBusinessId()))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private void insertBusiness(String id, String slug) {
        Business b = new Business();
        b.setId(id);
        b.setName("Test " + slug);
        b.setSlug(slug);
        b.setSettings("{}");
        businessRepository.save(b);
    }

    private static Permission perm(String id, String key, String desc) {
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
