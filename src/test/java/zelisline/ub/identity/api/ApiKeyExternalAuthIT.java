package zelisline.ub.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

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

import zelisline.ub.identity.domain.Permission;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.RolePermission;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.ApiKeyRepository;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/** Phase 8 Slice 1 — {@code X-API-Key} auth + scope-based {@code hasPermission}. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class ApiKeyExternalAuthIT {

    private static final String TENANT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbd1";
    private static final String ROLE = "22222222-0000-0000-0000-0000000000d1";
    private static final String P_KEY = "11111111-0000-0000-0000-000000000043";
    private static final String P_SALES_REP = "11111111-0000-0000-0000-000000000101";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ApiKeyRepository apiKeyRepository;
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

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User user;

    @BeforeEach
    void seed() {
        apiKeyRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("API Key Co");
        b.setSlug("api-key-co");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Main");
        branchRepository.save(br);

        permissionRepository.save(perm(P_KEY, "integrations.api_keys.manage", "k"));
        permissionRepository.save(perm(P_SALES_REP, "reports.sales.read", "sr"));

        Role role = new Role();
        role.setId(ROLE);
        role.setBusinessId(null);
        role.setRoleKey("api_ext_tester");
        role.setName("API Ext Tester");
        role.setSystem(true);
        roleRepository.save(role);

        for (String pid : List.of(P_KEY, P_SALES_REP)) {
            RolePermission rp = new RolePermission();
            rp.setId(new RolePermission.Id(ROLE, pid));
            rolePermissionRepository.save(rp);
        }

        user = new User();
        user.setBusinessId(TENANT);
        user.setEmail("apikey-it@test");
        user.setName("API Key IT");
        user.setRoleId(ROLE);
        user.setBranchId(br.getId());
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(user);
    }

    @Test
    void scopedApiKey_canCallSalesRegister_withoutJwtOrTestHeaders() throws Exception {
        String secret = mintApiKey(List.of("reports.sales.read"));

        mockMvc.perform(get("/api/v1/reports/sales/register")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-31")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-API-Key", secret))
                .andExpect(status().isOk());
    }

    @Test
    void apiKey_withoutScope_getsForbiddenOnSalesRegister() throws Exception {
        String secret = mintApiKey(List.of());

        mockMvc.perform(get("/api/v1/reports/sales/register")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-31")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-API-Key", secret))
                .andExpect(status().isForbidden());
    }

    @Test
    void apiKey_withWrongTenantHeader_getsForbidden() throws Exception {
        String secret = mintApiKey(List.of("reports.sales.read"));

        mockMvc.perform(get("/api/v1/reports/sales/register")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-31")
                        .header("X-Tenant-Id", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
                        .header("X-API-Key", secret))
                .andExpect(status().isForbidden());
    }

    @Test
    void bearerKposToken_sameAsHeader() throws Exception {
        String secret = mintApiKey(List.of("reports.sales.read"));

        mockMvc.perform(get("/api/v1/reports/sales/register")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-31")
                        .header("X-Tenant-Id", TENANT)
                        .header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk());
    }

    private String mintApiKey(List<String> scopes) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "label", "it-key",
                "scopes", scopes));

        MvcResult created = mockMvc.perform(post("/api/v1/api-keys")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(created.getResponse().getContentAsString());
        assertThat(json.hasNonNull("apiKey")).isTrue();
        String secret = json.get("apiKey").asText();
        if (!scopes.isEmpty()) {
            assertThat(apiKeyRepository.findAll().getFirst().getScopes()).contains(scopes.getFirst());
        }
        return secret;
    }

    private static Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }
}
