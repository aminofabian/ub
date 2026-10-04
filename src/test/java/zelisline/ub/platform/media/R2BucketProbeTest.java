package zelisline.ub.platform.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class R2BucketProbeTest {

    private final R2BucketProbe probe = new R2BucketProbe();

    private static R2Connection withEndpoint(String endpoint) {
        return new R2Connection(endpoint, "bucket", "key", "secret", "https://media.example.com");
    }

    @Test
    void endpointWithoutSchemeIsABadRequestNotAServerError() {
        assertThatThrownBy(() -> probe.verify(withEndpoint("acct.r2.cloudflarestorage.com")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("Invalid R2 endpoint");
    }

    @Test
    void malformedEndpointIsABadRequest() {
        assertThatThrownBy(() -> probe.verify(withEndpoint("https://bad host")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid R2 endpoint");
    }
}
