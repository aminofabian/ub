package zelisline.ub.exports.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

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

import zelisline.ub.exports.repository.ExportJobRepository;
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
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class ExportJobApiIT {

    private static final String TENANT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbc1";
    private static final String ROLE = "22222222-0000-0000-0000-0000000000c1";
    private static final String P_EXPORT = "11111111-0000-0000-0000-000000000106";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ExportJobRepository exportJobRepository;
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
        exportJobRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Export Co");
        b.setSlug("export-co");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Main");
        branchRepository.save(br);

        permissionRepository.save(perm(P_EXPORT, "reports.export", "export"));

        Role role = new Role();
        role.setId(ROLE);
        role.setBusinessId(null);
        role.setRoleKey("export_tester");
        role.setName("Export Tester");
        role.setSystem(true);
        roleRepository.save(role);

        RolePermission rp = new RolePermission();
        rp.setId(new RolePermission.Id(ROLE, P_EXPORT));
        rolePermissionRepository.save(rp);

        user = new User();
        user.setBusinessId(TENANT);
        user.setEmail("export-it@test");
        user.setName("Export IT");
        user.setRoleId(ROLE);
        user.setBranchId(br.getId());
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(user);
    }

    @Test
    void salesRegisterCsv_enqueueThenDownload_containsHeaderAndTotalsRow() throws Exception {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 31);
        String body = """
                {"reportKey":"sales_register","format":"csv","from":"%s","to":"%s"}
                """.formatted(from, to);

        MvcResult created = mockMvc.perform(post("/api/v1/reports/exports")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE)
                        .contentType(APPLICATION_JSON)
                        .content(body.trim()))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(created.getResponse().getContentAsString());
        assertThat(json.path("status").asText()).isEqualTo("completed");
        String jobId = json.path("id").asText();
        String downloadUrl = json.path("downloadUrl").asText();
        assertThat(downloadUrl).contains("/api/v1/reports/exports/" + jobId + "/download?token=");

        String token = downloadUrl.substring(downloadUrl.indexOf("token=") + "token=".length());

        MvcResult file = mockMvc.perform(get("/api/v1/reports/exports/" + jobId + "/download")
                        .param("token", token)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE))
                .andExpect(status().isOk())
                .andReturn();

        String csv = file.getResponse().getContentAsString();
        assertThat(csv).contains("day,branch_id,qty,revenue,cost,profit");
        assertThat(csv).contains("TOTAL,,");
    }

    @Test
    void sameIdempotencyKey_returnsExistingJob() throws Exception {
        LocalDate from = LocalDate.of(2026, 2, 1);
        LocalDate to = LocalDate.of(2026, 2, 28);
        String body = """
                {"reportKey":"sales_register","format":"csv","from":"%s","to":"%s"}
                """.formatted(from, to);

        String idem = "idem-export-1";

        MvcResult first = mockMvc.perform(post("/api/v1/reports/exports")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE)
                        .header("Idempotency-Key", idem)
                        .contentType(APPLICATION_JSON)
                        .content(body.trim()))
                .andExpect(status().isCreated())
                .andReturn();

        String id1 = objectMapper.readTree(first.getResponse().getContentAsString()).path("id").asText();

        MvcResult second = mockMvc.perform(post("/api/v1/reports/exports")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE)
                        .header("Idempotency-Key", idem)
                        .contentType(APPLICATION_JSON)
                        .content(body.trim()))
                .andExpect(status().isCreated())
                .andReturn();

        String id2 = objectMapper.readTree(second.getResponse().getContentAsString()).path("id").asText();
        assertThat(id2).isEqualTo(id1);
    }

    @Test
    void salesRegisterXlsx_enqueue_returnsZipMagicPrefix() throws Exception {
        LocalDate from = LocalDate.of(2026, 3, 1);
        LocalDate to = LocalDate.of(2026, 3, 10);
        String body = """
                {"reportKey":"sales_register","format":"xlsx","from":"%s","to":"%s"}
                """.formatted(from, to);

        MvcResult created = mockMvc.perform(post("/api/v1/reports/exports")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE)
                        .contentType(APPLICATION_JSON)
                        .content(body.trim()))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(created.getResponse().getContentAsString());
        assertThat(json.path("status").asText()).isEqualTo("completed");
        String jobId = json.path("id").asText();
        String downloadUrl = json.path("downloadUrl").asText();
        String token = downloadUrl.substring(downloadUrl.indexOf("token=") + "token=".length());

        byte[] bin = mockMvc.perform(get("/api/v1/reports/exports/" + jobId + "/download")
                        .param("token", token)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(bin.length).isGreaterThan(4);
        assertThat(bin[0]).isEqualTo((byte) 0x50);
        assertThat(bin[1]).isEqualTo((byte) 0x4b);
    }

    private static Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }
}
