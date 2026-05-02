package zelisline.ub.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.jayway.jsonpath.JsonPath;

import zelisline.ub.catalog.repository.CategoryRepository;
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
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class CatalogTaxonomyIT {

    private static final String TENANT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PERM_READ = "11111111-1111-1111-1111-111111111102";
    private static final String PERM_CAT_WRITE = "11111111-1111-1111-1111-111111111105";
    private static final String ROLE_OWNER = "22222222-2222-2222-2222-222222222201";

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
    private CategoryRepository categoryRepository;

    @Autowired
    private zelisline.ub.catalog.application.CatalogBootstrapService catalogBootstrapService;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User owner;

    @BeforeEach
    void seed() {
        categoryRepository.deleteAll();
        itemTypeRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        businessRepository.deleteAll();

        Business business = new Business();
        business.setId(TENANT);
        business.setName("Shop");
        business.setSlug("shop-taxonomy");
        business.setSettings("{}");
        businessRepository.save(business);
        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT);

        permissionRepository.save(perm(PERM_READ, "catalog.items.read", "Read"));
        permissionRepository.save(perm(PERM_CAT_WRITE, "catalog.categories.write", "Write taxonomy"));

        Role ownerRole = new Role();
        ownerRole.setId(ROLE_OWNER);
        ownerRole.setBusinessId(null);
        ownerRole.setRoleKey("owner");
        ownerRole.setName("Owner");
        ownerRole.setSystem(true);
        roleRepository.save(ownerRole);
        grant(ROLE_OWNER, PERM_READ);
        grant(ROLE_OWNER, PERM_CAT_WRITE);

        owner = new User();
        owner.setBusinessId(TENANT);
        owner.setEmail("owner@test");
        owner.setName("Owner");
        owner.setRoleId(ROLE_OWNER);
        owner.setStatus(UserStatus.ACTIVE);
        owner.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(owner);
    }

    @Test
    void newTenantHasThreeDefaultItemTypes() {
        assertThat(itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT)).hasSize(3);
    }

    @Test
    void patchCategoryParentCycleReturns400() throws Exception {
        String a = mockMvc.perform(post("/api/v1/categories")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"A\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String b = mockMvc.perform(post("/api/v1/categories")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"B\",\"parentId\":\"" + JsonPath.read(a, "$.id") + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        mockMvc.perform(patch("/api/v1/categories/" + JsonPath.read(a, "$.id"))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("{\"parentId\":\"" + JsonPath.read(b, "$.id") + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listItemTypesReturnsSeededKeys() throws Exception {
        String json = mockMvc.perform(get("/api/v1/item-types")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(json).contains("goods").contains("service").contains("kit");
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
