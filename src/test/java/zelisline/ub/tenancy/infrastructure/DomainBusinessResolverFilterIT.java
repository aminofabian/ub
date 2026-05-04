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
import zelisline.ub.tenancy.domain.TenantStatus;
import zelisline.ub.tenancy.repository.BusinessRepository;
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

    @MockitoBean
    private BusinessRepository businessRepository;

    @BeforeEach
    void resetRepositoryMock() {
        org.mockito.Mockito.reset(domainMappingRepository, businessRepository);
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
    void unknownHostWithExplicitTenantIdHeaderPassesThrough() throws Exception {
        org.mockito.BDDMockito.given(domainMappingRepository.findByDomainAndActiveTrue("palmart.co.ke"))
                .willReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(request -> {
                            request.setServerName("palmart.co.ke");
                            return request;
                        })
                        .header("X-Tenant-Id", "biz-explicit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x\",\"password\":\"y\"}"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == HttpStatus.NOT_FOUND.value()
                            && MediaType.APPLICATION_PROBLEM_JSON_VALUE
                                    .equals(result.getResponse().getContentType())) {
                        throw new AssertionError(
                                "X-Tenant-Id header must let the request reach the controller "
                                        + "instead of being short-circuited by tenant-not-found");
                    }
                });
    }

    @Test
    void bareLocalhostWithXTenantHostResolvesTenant() throws Exception {
        DomainMapping mapping = Mockito.mock(DomainMapping.class);
        org.mockito.BDDMockito.given(mapping.getBusinessId()).willReturn("biz-1");
        org.mockito.BDDMockito.given(domainMappingRepository.findByDomainAndActiveTrue("pal.localhost"))
                .willReturn(Optional.of(mapping));
        org.mockito.BDDMockito.given(businessRepository.findTenantStatusById("biz-1"))
                .willReturn(Optional.of(TenantStatus.ACTIVE));

        mockMvc.perform(get("/api/v1/openapi")
                        .with(request -> {
                            request.setServerName("localhost");
                            return request;
                        })
                        .header("X-Tenant-Host", "pal.localhost:3000"))
                .andExpect(status().isOk());
    }

    @Test
    void suspendedTenantOnMappedHostReturnsLocked() throws Exception {
        DomainMapping mapping = Mockito.mock(DomainMapping.class);
        org.mockito.BDDMockito.given(mapping.getBusinessId()).willReturn("biz-suspended");
        org.mockito.BDDMockito.given(domainMappingRepository.findByDomainAndActiveTrue("acme.palmart.co.ke"))
                .willReturn(Optional.of(mapping));
        org.mockito.BDDMockito.given(businessRepository.findTenantStatusById("biz-suspended"))
                .willReturn(Optional.of(TenantStatus.SUSPENDED));

        mockMvc.perform(get("/api/v1/businesses/me")
                        .with(request -> {
                            request.setServerName("acme.palmart.co.ke");
                            return request;
                        }))
                .andExpect(status().isLocked())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:problem:tenant-not-active"))
                .andExpect(jsonPath("$.tenantStatus").value("SUSPENDED"));
    }
}
