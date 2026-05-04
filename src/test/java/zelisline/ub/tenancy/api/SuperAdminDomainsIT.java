package zelisline.ub.tenancy.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import zelisline.ub.identity.domain.SuperAdmin;
import zelisline.ub.identity.repository.SuperAdminRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.DomainMapping;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Pins the super-admin domain admin contract: list / add / promote / delete.
 *
 * <p>Mirrors the tenant self-service tests so the console can offer the same
 * surface (especially "Delete" on non-primary rows) without surprising
 * behaviour, and exercises the {@code primaryDomain} field on
 * {@link zelisline.ub.tenancy.api.dto.BusinessResponse} that the app shell
 * uses for cross-origin handoff.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SuperAdminDomainsIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SuperAdminRepository superAdminRepository;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private DomainMappingRepository domainMappingRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String saToken;
    private String businessId;

    @BeforeEach
    void seed() throws Exception {
        superAdminRepository.deleteAll();
        domainMappingRepository.deleteAll();
        businessRepository.deleteAll();

        SuperAdmin admin = new SuperAdmin();
        admin.setEmail("ops@example.com");
        admin.setName("Ops");
        admin.setPasswordHash(passwordEncoder.encode("super-secret-pass"));
        admin.setActive(true);
        superAdminRepository.save(admin);

        Business business = new Business();
        business.setName("Acme");
        business.setSlug("acme");
        business.setSettings("{}");
        businessRepository.save(business);
        businessId = business.getId();

        String json = mockMvc.perform(post("/api/v1/super-admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ops@example.com","password":"super-secret-pass"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        saToken = JsonPath.read(json, "$.accessToken");
    }

    @Test
    void deletesNonPrimaryDomainSoftDelete() throws Exception {
        domainMappingRepository.save(domain(businessId, "primary.acme.com", true));
        String secondaryId = domainMappingRepository.save(
                domain(businessId, "extra.acme.com", false)).getId();

        mockMvc.perform(delete("/api/v1/super-admin/businesses/{b}/domains/{d}", businessId, secondaryId)
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/super-admin/businesses/{b}/domains", businessId)
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].domain").value("primary.acme.com"));
    }

    @Test
    void refusesToDeletePrimaryUntilAnotherIsPromoted() throws Exception {
        String primaryId = domainMappingRepository.save(
                domain(businessId, "primary.acme.com", true)).getId();
        String secondaryId = domainMappingRepository.save(
                domain(businessId, "extra.acme.com", false)).getId();

        mockMvc.perform(delete("/api/v1/super-admin/businesses/{b}/domains/{d}", businessId, primaryId)
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/super-admin/businesses/{b}/domains/{d}/primary", businessId, secondaryId)
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/super-admin/businesses/{b}/domains/{d}", businessId, primaryId)
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void businessResponseExposesPrimaryDomainHostname() throws Exception {
        domainMappingRepository.save(domain(businessId, "primary.acme.com", true));
        domainMappingRepository.save(domain(businessId, "extra.acme.com", false));

        mockMvc.perform(get("/api/v1/super-admin/businesses")
                        .header("Authorization", "Bearer " + saToken)
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + businessId + "')].primaryDomain")
                        .value("primary.acme.com"));
    }

    private static DomainMapping domain(String businessId, String host, boolean primary) {
        DomainMapping d = new DomainMapping();
        d.setBusinessId(businessId);
        d.setDomain(host);
        d.setPrimary(primary);
        d.setActive(true);
        return d;
    }
}
