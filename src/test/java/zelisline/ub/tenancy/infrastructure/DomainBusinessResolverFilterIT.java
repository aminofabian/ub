package zelisline.ub.tenancy.infrastructure;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.HttpStatus;
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
    void superAdminPathBypassesTenantResolution() throws Exception {
        mockMvc.perform(post("/api/v1/super-admin/auth/login")
                        .with(request -> {
                            request.setServerName("palmart.co.ke");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x\",\"password\":\"y\"}"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == HttpStatus.NOT_FOUND.value()) {
                        throw new AssertionError("super-admin path must bypass tenant-not-found filter");
                    }
                });

        verify(domainMappingRepository, never()).findByDomainAndActiveTrue(anyString());
    }

    @Test
    void actuatorHealthBypassesTenantResolution() throws Exception {
        mockMvc.perform(get("/actuator/health")
                        .with(request -> {
                            request.setServerName("palmart.co.ke");
                            return request;
                        }))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == HttpStatus.NOT_FOUND.value()) {
                        throw new AssertionError("actuator must bypass tenant-not-found filter");
                    }
                });

        verify(domainMappingRepository, never()).findByDomainAndActiveTrue(anyString());
    }

    @Test
    void unmappedHostWithExplicitTenantIdHeaderPassesThrough() throws Exception {
        org.mockito.BDDMockito.given(domainMappingRepository.findByDomainAndActiveTrue("palmart.co.ke"))
                .willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/businesses/me")
                        .with(request -> {
                            request.setServerName("palmart.co.ke");
                            return request;
                        })
                        .header("X-Tenant-Id", "dda962ef-ae81-4f79-bcd7-a40aa8da5700"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == HttpStatus.NOT_FOUND.value()
                            && "urn:problem:tenant-not-found".equals(result.getResponse().getContentAsString() == null
                                    ? null
                                    : extractProblemType(result.getResponse().getContentAsString()))) {
                        throw new AssertionError(
                                "X-Tenant-Id must let the request past the host resolver filter"
                        );
                    }
                });
    }

    private static String extractProblemType(String body) {
        int idx = body.indexOf("\"type\"");
        if (idx < 0) {
            return null;
        }
        int start = body.indexOf('"', body.indexOf(':', idx) + 1);
        int end = body.indexOf('"', start + 1);
        if (start < 0 || end < 0) {
            return null;
        }
        return body.substring(start + 1, end);
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
