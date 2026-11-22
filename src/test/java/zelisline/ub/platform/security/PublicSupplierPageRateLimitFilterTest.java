package zelisline.ub.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PublicSupplierPageRateLimitFilterTest {

    private final PublicSupplierPageRateLimitFilter filter =
            new PublicSupplierPageRateLimitFilter(new PublicSupplierPageRateLimiter());

    @Test
    void sixthPinTryInAMinuteIsRefused() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(call("POST", "/api/v1/public/suppliers/acme/page-seal/unlock", "10.0.0.1")).isEqualTo(200);
        }
        assertThat(call("POST", "/api/v1/public/suppliers/acme/page-seal/unlock", "10.0.0.1")).isEqualTo(429);
    }

    @Test
    void pinTriesFromManyAddressesStillRunOutForOnePage() throws Exception {
        int allowed = 0;
        for (int i = 0; i < 20; i++) {
            if (call("POST", "/api/v1/public/suppliers/acme/page-seal/unlock", "10.0.1." + i) == 200) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(10);
    }

    @Test
    void fourthSmsCodeInAMinuteIsRefused() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(call("POST", "/api/v1/public/suppliers/acme/page-seal/send-code", "10.0.2.1")).isEqualTo(200);
        }
        assertThat(call("POST", "/api/v1/public/suppliers/acme/page-seal/send-code", "10.0.2.1")).isEqualTo(429);
    }

    @Test
    void otherPathsAreLeftAlone() throws Exception {
        for (int i = 0; i < 300; i++) {
            assertThat(call("POST", "/api/v1/public/credits/claims", "10.0.3.1")).isEqualTo(200);
        }
    }

    private int call(String method, String uri, String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
