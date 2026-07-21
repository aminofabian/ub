package zelisline.ub.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.tenancy.domain.DomainMapping;
import zelisline.ub.tenancy.infrastructure.TenantRequestAttributes;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

class PublicHostBusinessResolverTest {

    @Test
    void prefersRequestAttribute() {
        DomainMappingRepository repo = mock(DomainMappingRepository.class);
        PublicHostBusinessResolver resolver = new PublicHostBusinessResolver(repo);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(TenantRequestAttributes.BUSINESS_ID, "biz-attr");
        assertThat(resolver.resolveOrThrow(req)).isEqualTo("biz-attr");
    }

    @Test
    void usesXTenantId() {
        DomainMappingRepository repo = mock(DomainMappingRepository.class);
        PublicHostBusinessResolver resolver = new PublicHostBusinessResolver(repo);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Tenant-Id", "biz-header");
        assertThat(resolver.resolveOrThrow(req)).isEqualTo("biz-header");
    }

    @Test
    void resolvesFromXTenantHostDomainMapping() {
        DomainMappingRepository repo = mock(DomainMappingRepository.class);
        DomainMapping mapping = new DomainMapping();
        mapping.setBusinessId("biz-host");
        when(repo.findByDomainAndActiveTrue("palmart.co.ke")).thenReturn(Optional.of(mapping));
        PublicHostBusinessResolver resolver = new PublicHostBusinessResolver(repo);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Tenant-Host", "palmart.co.ke");
        assertThat(resolver.resolveOrThrow(req)).isEqualTo("biz-host");
    }

    @Test
    void missingTenant_returns404() {
        DomainMappingRepository repo = mock(DomainMappingRepository.class);
        when(repo.findByDomainAndActiveTrue("unknown.example")).thenReturn(Optional.empty());
        PublicHostBusinessResolver resolver = new PublicHostBusinessResolver(repo);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setServerName("unknown.example");
        assertThatThrownBy(() -> resolver.resolveOrThrow(req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Tab not found");
    }
}
