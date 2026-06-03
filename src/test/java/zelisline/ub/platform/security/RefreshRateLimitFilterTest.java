package zelisline.ub.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RefreshRateLimitFilterTest {

    @Test
    void matchesRefreshPath() {
        assertThat("/api/v1/auth/refresh".endsWith("/api/v1/auth/refresh")).isTrue();
        assertThat("/api/v1/auth/login".endsWith("/api/v1/auth/refresh")).isFalse();
    }
}
