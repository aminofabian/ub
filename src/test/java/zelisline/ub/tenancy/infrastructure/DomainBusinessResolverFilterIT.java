package zelisline.ub.tenancy.infrastructure;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import zelisline.ub.tenancy.domain.DomainMapping;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Slice 1 DoD: "host-based resolution returns 404 on unknown host" — see
 * {@code docs/PHASE_1_PLAN.md} §1.6.
 *
 * <p>The resolver filter sits before authentication, so an unmapped host must
 * respond with {@code 404 application/problem+json} carrying the
 * {@code urn:problem:tenant-not-found} type. This test pins that contract.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DomainBusinessResolverFilterIT {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DomainMappingRepository domainMappingRepository;

    @BeforeEach
    void resetRepositoryMock() {
        org.mockito.Mockito.reset(domainMappingRepository);
    }

    @Test
    void unknownHostReturnsTenantNotFoundProblemJson() throws Exception {
        org.mockito.BDDMockito.given(domainMappingRepository.findByDomainAndActiveTrue("unknown.example.com"))
                .willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/businesses/me")
                        .with(request -> {
                            request.setServerName("unknown.example.com");
                            return request;
                        }))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:problem:tenant-not-found"))
                .andExpect(jsonPath("$.title").value("Tenant not found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void bareLocalhostWithXTenantHostResolvesTenant() throws Exception {
        DomainMapping mapping = Mockito.mock(DomainMapping.class);
        org.mockito.BDDMockito.given(mapping.getBusinessId()).willReturn("biz-1");
        org.mockito.BDDMockito.given(domainMappingRepository.findByDomainAndActiveTrue("pal.localhost"))
                .willReturn(Optional.of(mapping));

        mockMvc.perform(get("/api/v1/openapi")
                        .with(request -> {
                            request.setServerName("localhost");
                            return request;
                        })
                        .header("X-Tenant-Host", "pal.localhost:3000"))
                .andExpect(status().isOk());
    }
}
