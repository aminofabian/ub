package zelisline.ub.platform.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RemoteImageFetcherTest {

    @Test
    void acceptsPublicHttpsUrls() {
        assertThat(RemoteImageFetcher.parsePublicHttpUri(" https://1.1.1.1/a.png ").getHost()).isEqualTo("1.1.1.1");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1:8080/actuator",
            "http://localhost/a.png",
            "http://10.0.0.5/a.png",
            "http://192.168.1.1/a.png",
            "http://169.254.169.254/latest/meta-data",
            "http://[::1]/a.png",
            "http://0.0.0.0/a.png"
    })
    void refusesInternalHosts(String url) {
        assertThatThrownBy(() -> RemoteImageFetcher.parsePublicHttpUri(url))
                .hasMessageContaining("not allowed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ftp://example.com/a.png", "file:///etc/passwd", "javascript:alert(1)"})
    void refusesNonHttpUrls(String url) {
        assertThatThrownBy(() -> RemoteImageFetcher.parsePublicHttpUri(url))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
