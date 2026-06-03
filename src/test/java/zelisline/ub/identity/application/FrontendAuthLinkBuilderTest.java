package zelisline.ub.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

class FrontendAuthLinkBuilderTest {

    private static final String TENANT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    private BusinessRepository businessRepository;
    private FrontendAuthLinkBuilder builder;

    @BeforeEach
    void setUp() {
        businessRepository = org.mockito.Mockito.mock(BusinessRepository.class);
        builder = new FrontendAuthLinkBuilder(businessRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(
                builder, "emailVerificationUrlPrefix", "http://localhost:3000/verify-email?token=");
        org.springframework.test.util.ReflectionTestUtils.setField(
                builder, "passwordResetUrlPrefix", "http://localhost:3000/reset-password?token=");
        org.springframework.test.util.ReflectionTestUtils.setField(
                builder, "slugDomainSuffix", "kiosk.ke");
    }

    @Test
    void verificationLinkUsesTenantHostHeaderAndHostQueryHint() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/register");
        req.setScheme("https");
        req.setServerName("api.example.com");
        req.addHeader("X-Tenant-Host", "shop.kiosk.ke");

        String link = builder.verificationLink(req, TENANT, "raw-token");

        assertThat(link).isEqualTo("https://shop.kiosk.ke/verify-email?token=raw-token&host=shop.kiosk.ke");
    }

    @Test
    void passwordResetLinkFallsBackToBusinessSlugHostOnLocalhost() {
        Business business = new Business();
        business.setId(TENANT);
        business.setSlug("acme");
        org.mockito.Mockito.when(businessRepository.findByIdAndDeletedAtIsNull(TENANT))
                .thenReturn(java.util.Optional.of(business));

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/password/forgot");
        req.setScheme("http");
        req.setServerName("localhost");
        req.setServerPort(5050);

        String link = builder.passwordResetLink(req, TENANT, "reset-token");

        assertThat(link).isEqualTo(
                "http://localhost:3000/reset-password?token=reset-token&host=acme.kiosk.ke");
    }
}
