package zelisline.ub.integrations.webhook.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class WebhookTargetHostValidatorTest {

    @Test
    void rejectsAwsMetadataIpv4_inStrictMode() {
        WebhookTargetHostValidator v = new WebhookTargetHostValidator(false, false, 1500);
        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> v.validateResolvedHost("169.254.169.254"));
        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void rejectsLocalhost_inStrictMode() {
        WebhookTargetHostValidator v = new WebhookTargetHostValidator(false, false, 1500);
        assertThrows(ResponseStatusException.class, () -> v.validateResolvedHost("localhost"));
    }

    @Test
    void allowsLocalhost_whenOptIn() {
        WebhookTargetHostValidator v = new WebhookTargetHostValidator(true, false, 1500);
        v.validateResolvedHost("localhost");
    }
}
