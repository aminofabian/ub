package zelisline.ub.tenancy.api;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.DomainMapping;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Contract test for {@link zelisline.ub.tenancy.api.PublicHostResolveController}.
 *
 * <p>Exercises the host &rarr; slug lookup that the Next.js storefront uses to
 * resolve any mapped hostname (tenant subdomain or custom domain) without
 * putting the slug in the URL path.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicHostResolveIT {

    private static final String MAPPED_HOST = "acme.palmart.co.ke";
    private static final String BUSINESS_ID = "biz-acme-1";
    private static final String BUSINESS_SLUG = "acme";
    private static final String BUSINESS_NAME = "Acme Coffee";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DomainMappingRepository domainMappingRepository;

    @MockitoBean
    private BusinessRepository businessRepository;

    @BeforeEach
    void resetMocks() {
        Mockito.reset(domainMappingRepository, businessRepository);
    }

    @Test
    void mappedHost_returnsSlugAndStorefrontFlag() throws Exception {
        givenActiveMapping(MAPPED_HOST, BUSINESS_ID);
        givenBusiness(BUSINESS_ID, BUSINESS_SLUG, BUSINESS_NAME, storefrontEnabledJson());

        mockMvc.perform(get("/api/v1/public/host/resolve")
                        .param("host", MAPPED_HOST))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.slug").value(BUSINESS_SLUG))
                .andExpect(jsonPath("$.businessId").value(BUSINESS_ID))
                .andExpect(jsonPath("$.businessName").value(BUSINESS_NAME))
                .andExpect(jsonPath("$.storefrontEnabled").value(true));
    }

    @Test
    void mappedHost_withPortAndCase_isNormalized() throws Exception {
        givenActiveMapping(MAPPED_HOST, BUSINESS_ID);
        givenBusiness(BUSINESS_ID, BUSINESS_SLUG, BUSINESS_NAME, "{}");

        mockMvc.perform(get("/api/v1/public/host/resolve")
                        .param("host", "ACME.Palmart.co.ke:3000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(BUSINESS_SLUG))
                .andExpect(jsonPath("$.storefrontEnabled").value(false));
    }

    @Test
    void unknownHost_returns404ProblemJson() throws Exception {
        given(domainMappingRepository.findByDomainAndActiveTrue("unknown.example.com"))
                .willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/public/host/resolve")
                        .param("host", "unknown.example.com"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void deletedBusiness_returns404() throws Exception {
        givenActiveMapping(MAPPED_HOST, BUSINESS_ID);
        given(businessRepository.findByIdAndDeletedAtIsNull(BUSINESS_ID))
                .willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/public/host/resolve")
                        .param("host", MAPPED_HOST))
                .andExpect(status().isNotFound());
    }

    private void givenActiveMapping(String host, String businessId) {
        DomainMapping mapping = Mockito.mock(DomainMapping.class);
        given(mapping.getBusinessId()).willReturn(businessId);
        given(domainMappingRepository.findByDomainAndActiveTrue(host))
                .willReturn(Optional.of(mapping));
    }

    private void givenBusiness(String id, String slug, String name, String settings) {
        Business business = new Business();
        business.setId(id);
        business.setSlug(slug);
        business.setName(name);
        business.setSettings(settings);
        given(businessRepository.findByIdAndDeletedAtIsNull(id))
                .willReturn(Optional.of(business));
    }

    private static String storefrontEnabledJson() {
        return "{\"storefront\":{\"enabled\":true,\"catalogBranchId\":\"br-1\"}}";
    }
}
