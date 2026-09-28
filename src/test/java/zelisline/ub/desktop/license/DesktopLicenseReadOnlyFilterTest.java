package zelisline.ub.desktop.license;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

/**
 * An expired license must not strand a wedged install
 * (docs/scopes/DESKTOP_APP_AUDIT_SCOPE.md §12): sign-in, renewal and the
 * install-recovery endpoints (setup / reset / connect / reconnect) stay writable
 * even while every ordinary write is blocked.
 */
class DesktopLicenseReadOnlyFilterTest {

    private final DesktopLicenseReadOnlyFilter filter = new DesktopLicenseReadOnlyFilter(
        mock(DesktopLicenseGuard.class),
        new ObjectMapper()
    );

    private static HttpServletRequest request(String method, String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(uri);
        return request;
    }

    @Test
    void recoveryAndRenewalPathsAreWhitelisted() {
        assertTrue(DesktopLicenseReadOnlyFilter.isWriteWhitelisted("/api/v1/auth/login"));
        assertTrue(DesktopLicenseReadOnlyFilter.isWriteWhitelisted("/api/v1/license"));
        assertTrue(DesktopLicenseReadOnlyFilter.isWriteWhitelisted("/api/v1/desktop/setup"));
        assertTrue(DesktopLicenseReadOnlyFilter.isWriteWhitelisted("/api/v1/desktop/setup/reset"));
        assertTrue(DesktopLicenseReadOnlyFilter.isWriteWhitelisted("/api/v1/desktop/connect"));
        assertTrue(DesktopLicenseReadOnlyFilter.isWriteWhitelisted("/api/v1/desktop/reconnect"));
        assertTrue(
            DesktopLicenseReadOnlyFilter.isWriteWhitelisted("/api/v1/desktop/reconnect/refresh")
        );
    }

    @Test
    void ordinaryBusinessWritesAreNotWhitelisted() {
        assertFalse(DesktopLicenseReadOnlyFilter.isWriteWhitelisted("/api/v1/sales"));
        assertFalse(DesktopLicenseReadOnlyFilter.isWriteWhitelisted("/api/v1/products"));
        assertFalse(DesktopLicenseReadOnlyFilter.isWriteWhitelisted("/api/v1/customers"));
    }

    @Test
    void whitelistMatchingIsCaseInsensitive() {
        assertTrue(
            DesktopLicenseReadOnlyFilter.isWriteWhitelisted("/API/V1/DESKTOP/CONNECT")
        );
    }

    @Test
    void recoveryWritesBypassTheReadOnlyFilter() {
        assertTrue(filter.shouldNotFilter(request("POST", "/api/v1/desktop/setup")));
        assertTrue(filter.shouldNotFilter(request("POST", "/api/v1/desktop/setup/reset")));
        assertTrue(filter.shouldNotFilter(request("POST", "/api/v1/desktop/connect")));
        assertTrue(filter.shouldNotFilter(request("POST", "/api/v1/desktop/reconnect")));
        assertTrue(filter.shouldNotFilter(request("POST", "/api/v1/desktop/reconnect/refresh")));
    }

    @Test
    void readsAlwaysPassButOrdinaryWritesAreFiltered() {
        assertTrue(filter.shouldNotFilter(request("GET", "/api/v1/sales")));
        assertTrue(filter.shouldNotFilter(request("HEAD", "/api/v1/sales")));
        assertTrue(filter.shouldNotFilter(request("OPTIONS", "/api/v1/sales")));
        assertFalse(filter.shouldNotFilter(request("POST", "/api/v1/sales")));
        assertFalse(filter.shouldNotFilter(request("PUT", "/api/v1/products/1")));
        assertFalse(filter.shouldNotFilter(request("DELETE", "/api/v1/customers/1")));
    }

    @Test
    void nonApiWritesAreNeverFiltered() {
        assertTrue(filter.shouldNotFilter(request("POST", "/setup")));
        assertTrue(filter.shouldNotFilter(request("POST", "/ws")));
    }
}
