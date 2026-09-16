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
        req.setServerPort(443);
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

    @Test
    void verificationLinkNeverLeaksTheApiPortIntoAProxiedPublicHost() {
        // Production shape: the browser is on the tenant's public host, the Next.js
        // BFF holds the request and reaches the Java app over plain HTTP on the
        // app's own port (5050, see application.properties / Dockerfile). The BFF
        // does not forward X-Forwarded-Proto (header allowlist in backend-proxy.ts).
        // The app's connection port belongs to a different origin and must not
        // appear in a URL a user is expected to open.
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/register");
        req.setScheme("http");
        req.setServerName("kiosk.zelisline.com");
        req.setServerPort(5050);
        req.addHeader("X-Tenant-Host", "palmart.co.ke");

        String link = builder.verificationLink(req, TENANT, "raw-token");

        assertThat(link).isEqualTo(
                "https://palmart.co.ke/verify-email?token=raw-token&host=palmart.co.ke");
    }

    @Test
    void verificationLinkHonoursForwardedOriginHeadersWhenPresent() {
        // A proxy that does forward the original origin keeps working, including
        // a non-default public port.
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/register");
        req.setScheme("http");
        req.setServerName("10.0.0.7");
        req.setServerPort(5050);
        req.addHeader("X-Tenant-Host", "shop.kiosk.ke");
        req.addHeader("X-Forwarded-Proto", "https");
        req.addHeader("X-Forwarded-Port", "8443");

        String link = builder.verificationLink(req, TENANT, "raw-token");

        assertThat(link).isEqualTo(
                "https://shop.kiosk.ke:8443/verify-email?token=raw-token&host=shop.kiosk.ke");
    }

    @Test
    void verificationLinkKeepsAnExplicitHostPort() {
        // Local dev through the BFF: the browser host arrives as "host:port".
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/register");
        req.setScheme("http");
        req.setServerName("localhost");
        req.setServerPort(5050);
        req.addHeader("X-Tenant-Host", "localhost:3000");

        String link = builder.verificationLink(req, TENANT, "raw-token");

        assertThat(link).isEqualTo(
                "http://localhost:3000/verify-email?token=raw-token&host=localhost%3A3000");
    }

    @Test
    void verificationLinkUsesPort3000ForATenantLocalhostSubdomain() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/register");
        req.setScheme("http");
        req.setServerName("127.0.0.1");
        req.setServerPort(5050);
        req.addHeader("X-Tenant-Host", "acme.localhost");

        String link = builder.verificationLink(req, TENANT, "raw-token");

        assertThat(link).isEqualTo(
                "http://acme.localhost:3000/verify-email?token=raw-token&host=acme.localhost");
    }

    @Test
    void verificationLinkDropsADefaultForwardedPort() {
        // X-Forwarded-Port: 443 with https must not be rendered as ":443".
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/register");
        req.setScheme("http");
        req.setServerName("kiosk.zelisline.com");
        req.setServerPort(5050);
        req.addHeader("X-Tenant-Host", "shop.kiosk.ke");
        req.addHeader("X-Forwarded-Proto", "https, http");
        req.addHeader("X-Forwarded-Port", "443");

        String link = builder.verificationLink(req, TENANT, "raw-token");

        assertThat(link).isEqualTo(
                "https://shop.kiosk.ke/verify-email?token=raw-token&host=shop.kiosk.ke");
    }
}
