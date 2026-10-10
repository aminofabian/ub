package zelisline.ub.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.integrations.whatsapp.application.WhatsAppChannelCredentialsService;
import zelisline.ub.platform.api.dto.PlatformWhatsAppAppSecretDtos.AppSecretRow;

/**
 * Extra platform Meta app secrets feed inbound webhook verification: a WABA with more than one
 * subscribed Meta app must accept a signature from any of them.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PlatformWhatsAppAppSecretServiceIT {

    @Autowired
    private PlatformWhatsAppAppSecretService service;

    @Autowired
    private WhatsAppChannelCredentialsService credentialsService;

    @Test
    void addedSecretIsListedAndAcceptedForVerificationUntilRemoved() {
        AppSecretRow row = service.add("Second app", "second-app-secret-abcd");
        try {
            assertThat(row.id()).isNotBlank();
            assertThat(row.fingerprint()).isEqualTo("…abcd");
            assertThat(service.list()).extracting(AppSecretRow::id).contains(row.id());
            assertThat(credentialsService.candidateAppSecrets()).contains("second-app-secret-abcd");
        } finally {
            service.delete(row.id());
        }
        assertThat(credentialsService.candidateAppSecrets()).doesNotContain("second-app-secret-abcd");
    }

    @Test
    void blankSecretIsRejected() {
        assertThatThrownBy(() -> service.add("x", "   "))
                .isInstanceOf(ResponseStatusException.class);
    }
}
