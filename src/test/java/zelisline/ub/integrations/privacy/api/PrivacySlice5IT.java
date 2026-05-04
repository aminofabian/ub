package zelisline.ub.integrations.privacy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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

import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.identity.domain.Permission;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.RolePermission;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.integrations.privacy.application.CustomerAnonymisationService;
import zelisline.ub.integrations.privacy.application.UserAnonymisationService;
import zelisline.ub.integrations.privacy.repository.PrivacyExportJobRepository;
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
class PrivacySlice5IT {

    private static final String TENANT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbd5";
    private static final String ROLE = "22222222-0000-0000-0000-0000000000f2";
    private static final String P_PRIVACY = "11111111-0000-0000-0000-000000000108";

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
    private CustomerRepository customerRepository;
    @Autowired
    private CustomerPhoneRepository customerPhoneRepository;
    @Autowired
    private CreditAccountRepository creditAccountRepository;
    @Autowired
    private PrivacyExportJobRepository privacyExportJobRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User user;
    private String customerId;

    @BeforeEach
    void seed() {
        privacyExportJobRepository.deleteAll();
        customerPhoneRepository.deleteAll();
        creditAccountRepository.deleteAll();
        customerRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Privacy Test Co");
        b.setSlug("privacy-test-co");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Main");
        branchRepository.save(br);

        permissionRepository.save(perm(P_PRIVACY, "integrations.privacy.manage", "privacy"));

        Role role = new Role();
        role.setId(ROLE);
        role.setBusinessId(null);
        role.setRoleKey("privacy_tester");
        role.setName("Privacy Tester");
        role.setSystem(true);
        roleRepository.save(role);

        RolePermission rp = new RolePermission();
        rp.setId(new RolePermission.Id(ROLE, P_PRIVACY));
        rolePermissionRepository.save(rp);

        user = new User();
        user.setBusinessId(TENANT);
        user.setEmail("privacy-it@test");
        user.setName("Privacy IT");
        user.setRoleId(ROLE);
        user.setBranchId(br.getId());
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(user);

        Customer c = new Customer();
        c.setBusinessId(TENANT);
        c.setName("Jane Subject");
        c.setEmail("jane@subject.test");
        c.setNotes(" loyalty ");
        customerRepository.save(c);
        customerId = c.getId();

        CustomerPhone phone = new CustomerPhone();
        phone.setBusinessId(TENANT);
        phone.setCustomerId(customerId);
        phone.setPhone("+254700111222");
        phone.setPrimary(true);
        customerPhoneRepository.save(phone);

        CreditAccount acc = new CreditAccount();
        acc.setBusinessId(TENANT);
        acc.setCustomerId(customerId);
        acc.setBalanceOwed(new BigDecimal("100.00"));
        acc.setWalletBalance(BigDecimal.ZERO);
        acc.setLoyaltyPoints(0);
        creditAccountRepository.save(acc);
    }

    @Test
    void exportCustomer_zipContainsManifestAndProfileJson() throws Exception {
        String body = """
                {"subjectType":"customer","subjectId":"%s"}
                """.formatted(customerId);
        MvcResult created = mockMvc.perform(post("/api/v1/integrations/privacy/exports")
                        .contentType(APPLICATION_JSON)
                        .content(body)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode job = objectMapper.readTree(created.getResponse().getContentAsString());
        String jobId = job.get("id").asText();
        String downloadUrl = job.get("downloadUrl").asText();
        assertThat(downloadUrl).contains(jobId);
        assertThat(downloadUrl).contains("token=");

        int q = downloadUrl.indexOf("token=");
        String token = downloadUrl.substring(q + "token=".length());

        MvcResult file = mockMvc.perform(get("/api/v1/integrations/privacy/exports/" + jobId + "/download")
                        .param("token", token)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE))
                .andExpect(status().isOk())
                .andReturn();

        byte[] zipBytes = file.getResponse().getContentAsByteArray();
        assertThat(zipBytes.length).isGreaterThan(30);

        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            boolean sawManifest = false;
            boolean sawProfile = false;
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if ("manifest.json".equals(e.getName())) {
                    sawManifest = true;
                    byte[] buf = zin.readAllBytes();
                    JsonNode m = objectMapper.readTree(new String(buf, StandardCharsets.UTF_8));
                    assertThat(m.get("schema").asText()).isEqualTo("ub-privacy-export-v1");
                    assertThat(m.get("subjectId").asText()).isEqualTo(customerId);
                }
                if ("profile/customer.json".equals(e.getName())) {
                    sawProfile = true;
                    JsonNode p = objectMapper.readTree(new String(zin.readAllBytes(), StandardCharsets.UTF_8));
                    assertThat(p.get("name").asText()).isEqualTo("Jane Subject");
                }
                zin.closeEntry();
            }
            assertThat(sawManifest).isTrue();
            assertThat(sawProfile).isTrue();
        }
    }

    @Test
    void anonymiseCustomer_clearsPii_preservesCreditBalance() throws Exception {
        mockMvc.perform(post("/api/v1/integrations/privacy/customers/" + customerId + "/anonymise")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE))
                .andExpect(status().isNoContent());

        Customer c = customerRepository.findById(customerId).orElseThrow();
        assertThat(c.getName()).isEqualTo(CustomerAnonymisationService.REDACTED_NAME);
        assertThat(c.getEmail()).isNull();
        assertThat(c.getNotes()).isNull();
        assertThat(c.getAnonymisedAt()).isNotNull();
        assertThat(customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(customerId)).isEmpty();

        CreditAccount acc =
                creditAccountRepository.findByCustomerIdAndBusinessId(customerId, TENANT).orElseThrow();
        assertThat(acc.getBalanceOwed()).isEqualByComparingTo("100.00");
    }

    @Test
    void anonymiseUser_scrubsLoginDetails() throws Exception {
        User target = new User();
        target.setBusinessId(TENANT);
        target.setEmail("staff-erasure@test");
        target.setName("Staff Person");
        target.setRoleId(ROLE);
        target.setBranchId(user.getBranchId());
        target.setStatus(UserStatus.ACTIVE);
        target.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(target);
        String tid = target.getId();

        mockMvc.perform(post("/api/v1/integrations/privacy/users/" + tid + "/anonymise")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE))
                .andExpect(status().isNoContent());

        User re = userRepository.findById(tid).orElseThrow();
        assertThat(re.getEmail()).isEqualTo(UserAnonymisationService.redactedEmail(tid));
        assertThat(re.getName()).isEqualTo(CustomerAnonymisationService.REDACTED_NAME);
        assertThat(re.getPasswordHash()).isNull();
        assertThat(re.getPinHash()).isNull();
        assertThat(re.getPhone()).isNull();
        assertThat(re.getAnonymisedAt()).isNotNull();
        assertThat(re.getStatus()).isEqualTo(UserStatus.SUSPENDED.wire());
    }

    private static Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }
}
