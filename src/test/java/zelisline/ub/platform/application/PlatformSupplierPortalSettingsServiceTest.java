package zelisline.ub.platform.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import zelisline.ub.platform.repository.PlatformSupplierPortalSettingsRepository;

@ExtendWith(MockitoExtension.class)
class PlatformSupplierPortalSettingsServiceTest {

    @Mock
    private PlatformSupplierPortalSettingsRepository repository;

    @InjectMocks
    private PlatformSupplierPortalSettingsService service;

    @Test
    void renderTemplateSubstitutesPlaceholders() {
        String rendered = service.renderTemplate(
                "Hi {{supplier_name}} code {{claim_code}}",
                Map.of("supplier_name", "Acme", "claim_code", "123456"));
        assertThat(rendered).isEqualTo("Hi Acme code 123456");
    }

    @Test
    void renderTemplateLeavesUnknownPlaceholdersEmpty() {
        String rendered = service.renderTemplate("X {{missing}} Y", Map.of());
        assertThat(rendered).isEqualTo("X  Y");
    }
}
