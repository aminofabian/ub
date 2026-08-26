package zelisline.ub.catalog.api;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.repository.ItemPackOptionRepository;
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
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.domain.SupplierProductPackOffer;
import zelisline.ub.suppliers.repository.SupplierProductPackOfferRepository;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class ItemPackOptionsIT {

    private static final String TENANT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String TENANT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String PERM_READ = "11111111-0000-0000-0000-000000000040";
    private static final String PERM_WRITE = "11111111-0000-0000-0000-000000000041";
    private static final String ROLE_OWNER = "22222222-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mockMvc;

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
    private ItemRepository itemRepository;

    @Autowired
    private ItemPackOptionRepository itemPackOptionRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private SupplierProductRepository supplierProductRepository;

    @Autowired
    private SupplierProductPackOfferRepository supplierProductPackOfferRepository;

    @Autowired
    private CatalogBootstrapService catalogBootstrapService;

    @Autowired
    private ItemCatalogService itemCatalogService;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User ownerA;
    private String itemA;

    @BeforeEach
    void seed() {
        supplierProductPackOfferRepository.deleteAll();
        supplierProductRepository.deleteAll();
        supplierRepository.deleteAll();
        itemPackOptionRepository.deleteAll();
        itemRepository.deleteAll();
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

        itemA = itemCatalogService
                .createItem(TENANT_A, minimalItem("MNDZ-001", "Mandazi", goodsTypeId(TENANT_A)), null)
                .body()
                .id();
    }

    @Test
    void createsListsAndLoadsPacksInItemDetail() throws Exception {
        createOption("pack", "12", "Dozen", "120.00", 10);
        createOption("pack", "18", null, "170.00", 20);
        createOption("pack", "48", "Crate", "400.00", 30);

        MockHttpServletRequestBuilder list = get("/api/v1/items/{id}/pack-options", itemA);
        asTenantA(list);
        mockMvc.perform(list)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].unitsPerPack").value(12))
                .andExpect(jsonPath("$[0].packUnit").value("pack"))
                .andExpect(jsonPath("$[0].label").value("Dozen"))
                .andExpect(jsonPath("$[0].defaultPackPrice").value(120.0))
                .andExpect(jsonPath("$[2].unitsPerPack").value(48))
                .andExpect(jsonPath("$[2].label").value("Crate"));

        MockHttpServletRequestBuilder detail = get("/api/v1/items/{id}", itemA);
        asTenantA(detail);
        mockMvc.perform(detail)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packs.length()").value(3))
                .andExpect(jsonPath("$.packs[0].unitsPerPack").value(12))
                .andExpect(jsonPath("$.packs[1].unitsPerPack").value(18))
                .andExpect(jsonPath("$.packs[2].unitsPerPack").value(48));
    }

    @Test
    void patchUpdatesAndDeleteRemoves() throws Exception {
        String optionId = createOption("pack", "12", "Dozen", "120.00", 10);

        MockHttpServletRequestBuilder patch = patch("/api/v1/items/{id}/pack-options/{oid}", itemA, optionId)
                .contentType(APPLICATION_JSON)
                .content("""
                        {"label":"Family tray","unitsPerPack":24,"defaultPackPrice":250.00}
                        """);
        asTenantA(patch);
        mockMvc.perform(patch)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Family tray"))
                .andExpect(jsonPath("$.unitsPerPack").value(24))
                .andExpect(jsonPath("$.defaultPackPrice").value(250.0));

        MockHttpServletRequestBuilder remove = delete("/api/v1/items/{id}/pack-options/{oid}", itemA, optionId);
        asTenantA(remove);
        mockMvc.perform(remove).andExpect(status().isNoContent());

        MockHttpServletRequestBuilder list = get("/api/v1/items/{id}/pack-options", itemA);
        asTenantA(list);
        mockMvc.perform(list)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void rejectsUnitsPerPackNotGreaterThanOne() throws Exception {
        MockHttpServletRequestBuilder one = post("/api/v1/items/{id}/pack-options", itemA)
                .contentType(APPLICATION_JSON)
                .content("""
                        {"packUnit":"pack","unitsPerPack":1}
                        """);
        asTenantA(one);
        mockMvc.perform(one).andExpect(status().isBadRequest());

        MockHttpServletRequestBuilder fraction = post("/api/v1/items/{id}/pack-options", itemA)
                .contentType(APPLICATION_JSON)
                .content("""
                        {"packUnit":"pack","unitsPerPack":0.5}
                        """);
        asTenantA(fraction);
        mockMvc.perform(fraction).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsDuplicateShape() throws Exception {
        createOption("pack", "12", null, null, 0);

        MockHttpServletRequestBuilder duplicate = post("/api/v1/items/{id}/pack-options", itemA)
                .contentType(APPLICATION_JSON)
                .content("""
                        {"packUnit":"pack","unitsPerPack":12}
                        """);
        asTenantA(duplicate);
        mockMvc.perform(duplicate).andExpect(status().isConflict());
    }

    @Test
    void rejectsMissingPackUnit() throws Exception {
        MockHttpServletRequestBuilder missing = post("/api/v1/items/{id}/pack-options", itemA)
                .contentType(APPLICATION_JSON)
                .content("""
                        {"unitsPerPack":12}
                        """);
        asTenantA(missing);
        mockMvc.perform(missing).andExpect(status().isBadRequest());
    }

    @Test
    void packOptionsAreScopedToItemTenant() throws Exception {
        createOption("pack", "12", null, null, 0);

        MockHttpServletRequestBuilder crossTenant = get("/api/v1/items/{id}/pack-options", itemA)
                .header("X-Tenant-Id", TENANT_B)
                .header(TestAuthenticationFilter.HEADER_USER_ID, userIdForTenant(TENANT_B))
                .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER);
        mockMvc.perform(crossTenant).andExpect(status().isNotFound());
    }

    @Test
    void linkResponseIncludesMergedPacks() throws Exception {
        String option12 = createOption("pack", "12", "Dozen", "120.00", 10);
        createOption("pack", "18", null, "170.00", 20);
        createOption("pack", "48", "Crate", "400.00", 30);

        Supplier supplier = new Supplier();
        supplier.setBusinessId(TENANT_A);
        supplier.setName("Jacob's Cakes");
        supplier.setSupplierType("distributor");
        supplier.setStatus("active");
        supplierRepository.save(supplier);

        SupplierProduct link = new SupplierProduct();
        link.setSupplierId(supplier.getId());
        link.setItemId(itemA);
        link.setActive(true);
        supplierProductRepository.save(link);

        SupplierProductPackOffer override = new SupplierProductPackOffer();
        override.setSupplierProductId(link.getId());
        override.setItemPackOptionId(option12);
        override.setPackPrice(new java.math.BigDecimal("110.00"));
        override.setActive(true);
        override.setSortOrder(10);
        supplierProductPackOfferRepository.save(override);

        MockHttpServletRequestBuilder list = get("/api/v1/items/{id}/supplier-links", itemA);
        asTenantA(list);
        mockMvc.perform(list)
                .andExpect(status().isOk())
                // The synthetic SYS-UNASSIGNED link (primary) lists item defaults first;
                // the Jacob link carries the offer override.
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].packs.length()").value(3))
                .andExpect(jsonPath("$[0].packs[0].unitPrice").value(120.0))
                .andExpect(jsonPath("$[1].supplierName").value("Jacob's Cakes"))
                .andExpect(jsonPath("$[1].packs.length()").value(3))
                .andExpect(jsonPath("$[1].packs[0].id").value(option12))
                .andExpect(jsonPath("$[1].packs[0].unitsPerPack").value(12))
                .andExpect(jsonPath("$[1].packs[0].unitPrice").value(110.0))
                .andExpect(jsonPath("$[1].packs[0].eachPrice").value(9.17))
                .andExpect(jsonPath("$[1].packs[1].unitsPerPack").value(18))
                .andExpect(jsonPath("$[1].packs[1].unitPrice").value(170.0));
    }

    private String createOption(String packUnit, String units, String label, String price, int sortOrder)
            throws Exception {
        String payload = """
                {"packUnit":"%s","unitsPerPack":%s,"label":%s,"defaultPackPrice":%s,"sortOrder":%d}
                """.formatted(
                        packUnit,
                        units,
                        label == null ? "null" : "\"" + label + "\"",
                        price == null ? "null" : price,
                        sortOrder);
        MockHttpServletRequestBuilder create = post("/api/v1/items/{id}/pack-options", itemA)
                .contentType(APPLICATION_JSON)
                .content(payload);
        asTenantA(create);
        String response = mockMvc.perform(create)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private void asTenantA(MockHttpServletRequestBuilder builder) {
        builder.header("X-Tenant-Id", TENANT_A)
                .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER);
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
                null,
                null,
                null,
                null,
                null);
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
