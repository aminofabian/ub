package zelisline.ub.suppliers.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.domain.Item;
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
import zelisline.ub.suppliers.SupplierCodes;
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
class SuppliersApiIT {

    private static final String TENANT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String TENANT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    private static final String P_READ = "11111111-0000-0000-0000-000000000040";
    private static final String P_WRITE = "11111111-0000-0000-0000-000000000041";
    private static final String P_SUP_READ = "11111111-0000-0000-0000-000000000044";
    private static final String P_SUP_WRITE = "11111111-0000-0000-0000-000000000045";
    private static final String P_LINK = "11111111-0000-0000-0000-000000000046";
    private static final String ROLE_OWNER = "22222222-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    private SupplierRepository supplierRepository;

    @Autowired
    private SupplierContactRepository supplierContactRepository;

    @Autowired
    private SupplierProductRepository supplierProductRepository;

    @Autowired
    private CatalogBootstrapService catalogBootstrapService;

    @Autowired
    private ItemCatalogService itemCatalogService;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User ownerA;
    private String goodsTypeIdA;

    @BeforeEach
    void seed() {
        supplierProductRepository.deleteAll();
        supplierContactRepository.deleteAll();
        supplierRepository.deleteAll();
        itemRepository.deleteAll();
        itemTypeRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        businessRepository.deleteAll();

        insertBusiness(TENANT_A, "tenant-a-sup");
        insertBusiness(TENANT_B, "tenant-b-sup");
        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT_A);
        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT_B);
        goodsTypeIdA = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT_A).getFirst().getId();

        permissionRepository.save(perm(P_READ, "catalog.items.read", "r"));
        permissionRepository.save(perm(P_WRITE, "catalog.items.write", "w"));
        permissionRepository.save(perm(P_SUP_READ, "suppliers.read", "sr"));
        permissionRepository.save(perm(P_SUP_WRITE, "suppliers.write", "sw"));
        permissionRepository.save(perm(P_LINK, "catalog.items.link_suppliers", "lk"));

        Role ownerRole = new Role();
        ownerRole.setId(ROLE_OWNER);
        ownerRole.setBusinessId(null);
        ownerRole.setRoleKey("owner");
        ownerRole.setName("Owner");
        ownerRole.setSystem(true);
        roleRepository.save(ownerRole);
        grant(ROLE_OWNER, P_READ);
        grant(ROLE_OWNER, P_WRITE);
        grant(ROLE_OWNER, P_SUP_READ);
        grant(ROLE_OWNER, P_SUP_WRITE);
        grant(ROLE_OWNER, P_LINK);

        ownerA = user("owner-a@sup", TENANT_A);
        userRepository.save(ownerA);
        userRepository.save(user("owner-b@sup", TENANT_B));
    }

    @Test
    void tenantCannotReadOtherTenantSuppliers() throws Exception {
        String supplierB = createSupplier(TENANT_B, "Vendor B Only");
        mockMvc.perform(get("/api/v1/suppliers/" + supplierB)
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isNotFound());
    }

    @Test
    void twoPrimariesNormalizeToOneWinner() throws Exception {
        String supplierA = createSupplier(TENANT_A, "Vendor A");
        String supplierB = createSupplier(TENANT_A, "Vendor B");
        String itemId = createSellableItem("SKU-2P", "Two Primary");

        String linkA = addLink(itemId, supplierA, true);
        String linkB = addLink(itemId, supplierB, true);
        assertThat(linkA).isNotEqualTo(linkB);

        mockMvc.perform(get("/api/v1/items/" + itemId + "/supplier-links")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.primary == true)].id").isNotEmpty());

        long primaryCount = supplierProductRepository.findByItemIdAndDeletedAtIsNull(itemId).stream()
                .filter(sp -> sp.isActive() && sp.isPrimaryLink())
                .count();
        assertThat(primaryCount).isEqualTo(1);
    }

    @Test
    void removingLastLinkDeactivatesSellableStockedItem() throws Exception {
        String itemId = createItemRowWithoutSupplierLinks();
        String supplierA = createSupplier(TENANT_A, "Only Vendor");
        String linkId = addLink(itemId, supplierA, true);

        mockMvc.perform(delete("/api/v1/items/" + itemId + "/supplier-links/" + linkId)
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isNoContent());

        assertThat(itemRepository.findById(itemId).orElseThrow().isActive()).isFalse();
    }

    @Test
    void itemCreateAutoCreatesSystemSupplierLink() {
        String itemId = createSellableItem("SKU-AUTO", "Auto link");
        long nSys = supplierRepository.findAll().stream()
                .filter(s -> SupplierCodes.SYSTEM_UNASSIGNED.equals(s.getCode()))
                .count();
        assertThat(nSys).isGreaterThanOrEqualTo(1);
        assertThat(supplierProductRepository.existsActiveByItemId(itemId)).isTrue();
    }

    private String createItemRowWithoutSupplierLinks() {
        Item it = new Item();
        it.setBusinessId(TENANT_A);
        it.setSku("SKU-ORPHAN");
        it.setName("Orphan");
        it.setItemTypeId(goodsTypeIdA);
        it.setUnitType("each");
        it.setSellable(true);
        it.setStocked(true);
        itemRepository.save(it);
        return it.getId();
    }

    private String createSellableItem(String sku, String name) {
        return itemCatalogService.createItem(
                TENANT_A,
                minimalItemJson(sku, name),
                null
        ).body().id();
    }

    private CreateItemRequest minimalItemJson(String sku, String name) {
        return new CreateItemRequest(
                sku, null, name, null, goodsTypeIdA, null, null, null,
                false, true, true,
                null, null, null, null, null, null, null, null, null, null, null
        );
    }

    private String createSupplier(String tenantId, String name) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/suppliers")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}")
                        .header("X-Tenant-Id", tenantId)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, userIdForTenant(tenantId))
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(r.getResponse().getContentAsString());
        return node.get("id").asText();
    }

    private String addLink(String itemId, String supplierId, boolean setPrimary) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("supplierId", supplierId)
                .put("setPrimary", setPrimary)
                .toString();
        MvcResult r = mockMvc.perform(post("/api/v1/items/" + itemId + "/supplier-links")
                        .contentType(APPLICATION_JSON)
                        .content(body)
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.id");
    }

    private void insertBusiness(String id, String slug) {
        Business b = new Business();
        b.setId(id);
        b.setName("B " + id.substring(0, 8));
        b.setSlug(slug);
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

    private User user(String email, String tenant) {
        User u = new User();
        u.setBusinessId(tenant);
        u.setEmail(email);
        u.setName(email);
        u.setRoleId(ROLE_OWNER);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        return u;
    }

    private String userIdForTenant(String tenantId) {
        return userRepository.findAll().stream()
                .filter(u -> tenantId.equals(u.getBusinessId()))
                .findFirst()
                .orElseThrow()
                .getId();
    }
}
