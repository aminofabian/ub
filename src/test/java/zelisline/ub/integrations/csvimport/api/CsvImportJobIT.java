package zelisline.ub.integrations.csvimport.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.identity.domain.Permission;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.RolePermission;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.integrations.csvimport.application.ImportJobRunner;
import zelisline.ub.integrations.csvimport.domain.ImportJob;
import zelisline.ub.integrations.csvimport.repository.ImportJobRepository;
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
class CsvImportJobIT {

    private static final String TENANT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbd4";
    private static final String ROLE = "22222222-0000-0000-0000-0000000000f1";
    private static final String P_IMPORT = "11111111-0000-0000-0000-000000000045";

    private static Path jobStorageDir;

    @DynamicPropertySource
    static void importJobProps(DynamicPropertyRegistry reg) throws Exception {
        jobStorageDir = Files.createTempDirectory("ub-import-job-it");
        reg.add("app.integrations.import.jobs.storage-dir", () -> jobStorageDir.toAbsolutePath().toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
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
    private ItemRepository itemRepository;
    @Autowired
    private ImportJobRepository importJobRepository;
    @Autowired
    private ImportJobRunner importJobRunner;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User user;

    @BeforeEach
    void seed() {
        importJobRepository.deleteAll();
        itemRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Job Import Co");
        b.setSlug("job-import-co");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Main");
        branchRepository.save(br);

        permissionRepository.save(perm(P_IMPORT, "integrations.imports.manage", "csv"));

        Role role = new Role();
        role.setId(ROLE);
        role.setBusinessId(null);
        role.setRoleKey("import_job_tester");
        role.setName("Import Job Tester");
        role.setSystem(true);
        roleRepository.save(role);

        RolePermission rp = new RolePermission();
        rp.setId(new RolePermission.Id(ROLE, P_IMPORT));
        rolePermissionRepository.save(rp);

        user = new User();
        user.setBusinessId(TENANT);
        user.setEmail("csv-import-job-it@test");
        user.setName("CSV Import Job IT");
        user.setRoleId(ROLE);
        user.setBranchId(br.getId());
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(user);

        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT);
    }

    @Test
    void asyncItemsCommit_jobCompletes_payloadRemoved() throws Exception {
        String csv = """
                sku,name,item_type_key
                JOB-SKU-1,One,goods
                JOB-SKU-2,Two,goods
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "items.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        MvcResult accepted = mockMvc.perform(multipart("/api/v1/integrations/imports/jobs/items")
                        .file(file)
                        .param("dryRun", "false")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE)
                        .contentType(MULTIPART_FORM_DATA))
                .andExpect(status().isAccepted())
                .andReturn();

        JsonNode created = objectMapper.readTree(accepted.getResponse().getContentAsString());
        String jobId = created.get("jobId").asText();

        Path staged = jobStorageDir.resolve(jobId + ".csv");
        assertThat(Files.exists(staged)).isTrue();

        importJobRunner.processNext();

        assertThat(Files.exists(staged)).isFalse();

        ImportJob job = importJobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.completed);
        assertThat(job.getRowsCommitted()).isEqualTo(2);
        assertThat(job.getRowsTotal()).isEqualTo(2);

        mockMvc.perform(get("/api/v1/integrations/imports/jobs/" + jobId)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE))
                .andExpect(status().isOk())
                .andExpect(r -> {
                    JsonNode body = objectMapper.readTree(r.getResponse().getContentAsString());
                    assertThat(body.get("status").asText()).isEqualTo("completed");
                    assertThat(body.get("rowsCommitted").asInt()).isEqualTo(2);
                });

        assertThat(itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(TENANT, "JOB-SKU-1")).isPresent();
    }

    private static Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }
}
