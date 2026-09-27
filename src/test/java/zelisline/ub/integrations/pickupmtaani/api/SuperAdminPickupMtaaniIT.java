package zelisline.ub.integrations.pickupmtaani.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import zelisline.ub.identity.domain.SuperAdmin;
import zelisline.ub.identity.repository.SuperAdminRepository;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient.AccountInfo;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient.BusinessInfo;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SuperAdminPickupMtaaniIT {

    private static final String API_KEY = "tenant-pum-key";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SuperAdminRepository superAdminRepository;
    @Autowired
    private BusinessRepository businessRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    @SuppressWarnings("unused")
    private PickupMtaaniClient pickupMtaaniClient;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private String saToken;
    private String businessId;

    @BeforeEach
    void seed() throws Exception {
        superAdminRepository.deleteAll();
        businessRepository.deleteAll();

        SuperAdmin admin = new SuperAdmin();
        admin.setEmail("ops-pum@example.com");
        admin.setName("Ops");
        admin.setPasswordHash(passwordEncoder.encode("super-secret-pass"));
        admin.setActive(true);
        superAdminRepository.save(admin);

        Business business = new Business();
        business.setName("PUM Credential Co");
        business.setSlug("pum-credential-co");
        business.setActive(true);
        business.setSettings("{}");
        businessRepository.save(business);
        businessId = business.getId();

        String json = mockMvc.perform(post("/api/v1/super-admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ops-pum@example.com","password":"super-secret-pass"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        saToken = JsonPath.read(json, "$.accessToken");
    }

    private String base() {
        return "/api/v1/super-admin/businesses/" + businessId + "/integrations/pickup-mtaani";
    }

    @Test
    void get_beforeSave_disconnected() throws Exception {
        mockMvc.perform(get(base()).header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasApiKey").value(false))
                .andExpect(jsonPath("$.status").value("disconnected"));
    }

    @Test
    void put_savesEncryptsAndConnects_withoutEchoingKey() throws Exception {
        when(pickupMtaaniClient.getAccount(API_KEY)).thenReturn(new AccountInfo("single_business", 1, 1));
        when(pickupMtaaniClient.getBusiness(API_KEY)).thenReturn(new BusinessInfo(99L, "PUM Shop", "0712", "PIN"));

        String json = mockMvc.perform(put(base())
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"apiKey\":\"" + API_KEY + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasApiKey").value(true))
                .andExpect(jsonPath("$.businessId").value(99))
                .andExpect(jsonPath("$.businessName").value("PUM Shop"))
                .andExpect(jsonPath("$.accountMode").value("single_business"))
                .andExpect(jsonPath("$.status").value("connected"))
                .andReturn().getResponse().getContentAsString();

        assertThat(json).doesNotContain(API_KEY);
    }

    @Test
    void put_multiBusinessKey_flagsError() throws Exception {
        when(pickupMtaaniClient.getAccount(API_KEY)).thenReturn(new AccountInfo("multi_business", 3, 5));

        mockMvc.perform(put(base())
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"apiKey\":\"" + API_KEY + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountMode").value("multi_business"))
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void put_blankKey_badRequest() throws Exception {
        mockMvc.perform(put(base())
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"apiKey\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void delete_disconnects() throws Exception {
        when(pickupMtaaniClient.getAccount(any())).thenReturn(new AccountInfo("single_business", 1, 1));
        when(pickupMtaaniClient.getBusiness(any())).thenReturn(new BusinessInfo(99L, "PUM Shop", "0712", "PIN"));

        mockMvc.perform(put(base())
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"apiKey\":\"" + API_KEY + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete(base()).header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasApiKey").value(false))
                .andExpect(jsonPath("$.status").value("disconnected"));
    }
}
