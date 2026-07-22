package zelisline.ub.globalcatalog.api;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.finance.application.LedgerBootstrapService;
import zelisline.ub.finance.repository.LedgerAccountRepository;
import zelisline.ub.globalcatalog.application.GlobalCatalogJobRunner;
import zelisline.ub.globalcatalog.domain.GlobalCatalog;
import zelisline.ub.globalcatalog.domain.GlobalProduct;
import zelisline.ub.globalcatalog.repository.GlobalCatalogJobRepository;
import zelisline.ub.globalcatalog.repository.GlobalCatalogRepository;
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
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class GlobalCatalogJobIT {

    private static final String TENANT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
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
    private CatalogBootstrapService catalogBootstrapService;

    @Autowired
    private LedgerBootstrapService ledgerBootstrapService;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private SupplierProductRepository supplierProductRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private GlobalCatalogRepository globalCatalogRepository;

    @Autowired
    private GlobalProductRepository globalProductRepository;

    @Autowired
    private GlobalCatalogJobRepository globalCatalogJobRepository;

    @Autowired
    private GlobalCatalogJobRunner globalCatalogJobRunner;

    private User ownerA;
    private String branchId;
    private String catalogId;

    @BeforeEach
    void seed() {
        globalCatalogJobRepository.deleteAll();
        supplierProductRepository.deleteAll();
        supplierRepository.deleteAll();
        itemRepository.deleteAll();
        globalProductRepository.deleteAll();
        globalCatalogRepository.deleteAll();
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

        permissionRepository.save(perm(PERM_GLOBAL_ADOPT, "catalog.global.adopt", "Adopt global products"));

        Role ownerRole = new Role();
        ownerRole.setId(ROLE_OWNER);
        ownerRole.setRoleKey("owner");
        ownerRole.setName("Owner");
        ownerRole.setSystem(true);
        roleRepository.save(ownerRole);
        RolePermission rp = new RolePermission();
        rp.setId(new RolePermission.Id(ROLE_OWNER, PERM_GLOBAL_ADOPT));
        rolePermissionRepository.save(rp);

        ownerA = new User();
        ownerA.setBusinessId(TENANT_A);
        ownerA.setEmail("owner-job@test");
        ownerA.setName("Owner Job");
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
        catalogId = globalCatalogRepository.save(catalog).getId();
    }

    @Test
    void syncAdoptRejectsOverThreshold() throws Exception {
        List<String> ids = seedPublishedProducts(26);
        String lines = ids.stream()
                .map(id -> "{\"globalProductId\":\"" + id + "\",\"sellingPrice\":90,\"buyingPrice\":70}")
                .collect(Collectors.joining(","));
        String body = "{\"openingBranchId\":\"" + branchId + "\",\"lines\":[" + lines + "]}";

        mockMvc.perform(post("/api/v1/global-catalog/adopt")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adoptJobEnqueuesAndCompletesViaRunner() throws Exception {
        List<String> ids = seedPublishedProducts(2);
        String lines = ids.stream()
                .map(id -> "{\"globalProductId\":\"" + id + "\",\"sellingPrice\":90,\"buyingPrice\":70}")
                .collect(Collectors.joining(","));
        String body = "{\"openingBranchId\":\"" + branchId + "\",\"lines\":[" + lines + "]}";

        String createJson = mockMvc.perform(post("/api/v1/global-catalog/adopt/jobs")
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String jobId = JsonPath.read(createJson, "$.jobId");

        mockMvc.perform(get("/api/v1/global-catalog/adopt/jobs/{jobId}", jobId)
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.kind").value("adopt"));

        globalCatalogJobRunner.processNext();

        mockMvc.perform(get("/api/v1/global-catalog/adopt/jobs/{jobId}", jobId)
                        .header("X-Tenant-Id", TENANT_A)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, ownerA.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.rowsCommitted").value(2))
                .andExpect(jsonPath("$.result.importedCount").value(2));

        org.assertj.core.api.Assertions.assertThat(itemRepository.findByBusinessIdAndDeletedAtIsNull(TENANT_A))
                .hasSize(2);
    }

    private List<String> seedPublishedProducts(int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            GlobalProduct product = new GlobalProduct();
            product.setCatalogId(catalogId);
            product.setName("Job Product " + i);
            product.setBarcode("9" + String.format("%012d", i));
            product.setUnitType("each");
            product.setRecommendedBuyingPrice(new BigDecimal("65.00"));
            product.setRecommendedSellingPrice(new BigDecimal("80.00"));
            product.setStatus("published");
            product.setSortOrder(i);
            product.setSkuTemplate("JOB-SKU-" + i);
            ids.add(globalProductRepository.save(product).getId());
        }
        return ids;
    }

    private static Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }
}
