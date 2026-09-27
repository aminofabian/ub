package zelisline.ub.integrations.pickupmtaani.api;

import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient.GeoOption;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
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
class PickupMtaaniIntegrationsIT {

    private static final String TENANT = "cccccccc-cccc-cccc-cccc-ccccccccccd7";
    private static final String ROLE = "33333333-0000-0000-0000-0000000000f4";
    private static final String API_KEY = "tenant-pum-key";

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
    private RoleRepository roleRepository;
    @Autowired
    private CredentialEncryptionService credentialEncryptionService;

    @MockitoBean
    @SuppressWarnings("unused")
    private PickupMtaaniClient pickupMtaaniClient;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User user;

    @BeforeEach
    void seed() {
        userRepository.deleteAll();
        roleRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Pickup Mtaani IT Co");
        b.setSlug("pickup-mtaani-it-co");
        b.setSettings("{}");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Main");
        branchRepository.save(br);

        Role role = new Role();
        role.setId(ROLE);
        role.setRoleKey("pickup_mtaani_tester");
        role.setName("Pickup Mtaani Tester");
        role.setSystem(true);
        roleRepository.save(role);

        user = new User();
        user.setBusinessId(TENANT);
        user.setEmail("pickup-mtaani-it@test");
        user.setName("Pickup Mtaani IT");
        user.setRoleId(ROLE);
        user.setBranchId(br.getId());
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(user);
    }

    @Test
    void get_beforeCredential_returnsDisconnectedDefaults() throws Exception {
        mockMvc.perform(auth(get("/api/v1/integrations/pickup-mtaani")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.status").value("disconnected"))
                .andExpect(jsonPath("$.ready").value(false))
                // credential fields are not exposed to the merchant
                .andExpect(jsonPath("$.hasApiKey").doesNotExist())
                .andExpect(jsonPath("$.accountMode").doesNotExist())
                .andExpect(jsonPath("$.lastVerifiedAt").doesNotExist())
                .andExpect(jsonPath("$.businessId").doesNotExist());
    }

    @Test
    void put_configOnly_thenGet_roundTrips() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "enabled", true,
                "originAgentId", 362,
                "originAgentName", "Kahawa West Agent",
                "feeMode", "absorb"));

        mockMvc.perform(auth(put("/api/v1/integrations/pickup-mtaani")
                        .contentType(APPLICATION_JSON).content(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originAgentId").value(362))
                .andExpect(jsonPath("$.feeMode").value("absorb"));

        mockMvc.perform(auth(get("/api/v1/integrations/pickup-mtaani")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originAgentName").value("Kahawa West Agent"))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void put_withApiKey_isRejected() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("enabled", true, "apiKey", API_KEY));

        mockMvc.perform(auth(put("/api/v1/integrations/pickup-mtaani")
                        .contentType(APPLICATION_JSON).content(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void agents_notConnected_conflict() throws Exception {
        mockMvc.perform(auth(get("/api/v1/integrations/pickup-mtaani/agents")))
                .andExpect(status().isConflict());
    }

    @Test
    void agents_whenCredentialPresent_returnsOptions() throws Exception {
        // The merchant cannot set a key, but super-admin can; simulate a connected tenant.
        String enc = credentialEncryptionService.encryptSecret(API_KEY);
        Business b = businessRepository.findById(TENANT).orElseThrow();
        b.setSettings("{\"pickupMtaani\":{\"apiKeyEnc\":\"" + enc + "\",\"accountMode\":\"single_business\"}}");
        businessRepository.save(b);
        when(pickupMtaaniClient.listAgents(API_KEY, 5L, "origin", null))
                .thenReturn(List.of(new GeoOption(362L, "Kahawa West Agent", 5L, 2L, 3L)));

        mockMvc.perform(auth(get("/api/v1/integrations/pickup-mtaani/agents"))
                        .param("locationId", "5")
                        .param("purpose", "origin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(362))
                .andExpect(jsonPath("$[0].name").value("Kahawa West Agent"));
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request
                .header("X-Tenant-Id", TENANT)
                .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE);
    }
}
