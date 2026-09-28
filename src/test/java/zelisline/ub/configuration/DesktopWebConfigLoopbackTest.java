package zelisline.ub.configuration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

/**
 * The desktop security chain restricts the install-mutating pre-auth endpoints
 * (setup / reset / connect) to loopback. These cases pin the address matching
 * so a LAN peer can never be treated as local.
 */
class DesktopWebConfigLoopbackTest {

    private static HttpServletRequest requestFrom(String remoteAddr) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
        return request;
    }

    @Test
    void acceptsIpv4Loopback() {
        assertTrue(DesktopWebConfig.isLoopbackRequest(requestFrom("127.0.0.1")));
    }

    @Test
    void acceptsIpv6Loopback() {
        assertTrue(DesktopWebConfig.isLoopbackRequest(requestFrom("::1")));
        assertTrue(DesktopWebConfig.isLoopbackRequest(requestFrom("0:0:0:0:0:0:0:1")));
    }

    @Test
    void acceptsIpv4MappedIpv6Loopback() {
        assertTrue(DesktopWebConfig.isLoopbackRequest(requestFrom("::ffff:127.0.0.1")));
    }

    @Test
    void rejectsLanAddresses() {
        assertFalse(DesktopWebConfig.isLoopbackRequest(requestFrom("192.168.1.20")));
        assertFalse(DesktopWebConfig.isLoopbackRequest(requestFrom("10.0.0.5")));
        assertFalse(DesktopWebConfig.isLoopbackRequest(requestFrom("172.16.4.9")));
        assertFalse(DesktopWebConfig.isLoopbackRequest(requestFrom("203.0.113.7")));
    }

    @Test
    void rejectsMissingOrUnparseableAddress() {
        assertFalse(DesktopWebConfig.isLoopbackRequest(requestFrom(null)));
        assertFalse(DesktopWebConfig.isLoopbackRequest(requestFrom("")));
        assertFalse(DesktopWebConfig.isLoopbackRequest(requestFrom("   ")));
        assertFalse(DesktopWebConfig.isLoopbackRequest(requestFrom("not-an-ip")));
    }
}
