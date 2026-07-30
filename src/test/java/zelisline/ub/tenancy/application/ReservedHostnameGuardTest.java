package zelisline.ub.tenancy.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ReservedHostnameGuardTest {

    private final ReservedHostnameGuard guard = new ReservedHostnameGuard(
            List.of("kiosk.ke", "www.kiosk.ke", "palmart.co.ke"),
            "kiosk.ke"
    );

    @Test
    void allowsNormalCustomHostname() {
        assertThatCode(() -> guard.assertClaimable("shop.mama-njeri.co.ke"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsPlatformApex() {
        assertThatThrownBy(() -> guard.assertClaimable("kiosk.ke"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsPlatformSubdomainClaim() {
        assertThatThrownBy(() -> guard.assertClaimable("acme.kiosk.ke"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> guard.assertClaimable("  "))
                .isInstanceOf(ResponseStatusException.class);
    }
}
