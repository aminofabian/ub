package zelisline.ub.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.tenancy.api.dto.FeatureFlagsPatchRequest;
import zelisline.ub.tenancy.repository.BranchRepository;

class StorefrontSettingsServiceFeatureFlagTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BranchRepository branchRepository = mock(BranchRepository.class);
    private final StorefrontSettingsService service = new StorefrontSettingsService(objectMapper, branchRepository);

    @Test
    void mergeFeatureFlags_enablesButcherPos() throws Exception {
        String merged = service.mergeFeatureFlags(
                "{}",
                new FeatureFlagsPatchRequest(null, true, null, null, null, null, null, null, null, null, null));

        assertThat(objectMapper.readTree(merged).path("featureFlags").path("butcher_pos.enabled").asBoolean())
                .isTrue();
    }

    @Test
    void mergeFeatureFlags_disablesButcherPos() throws Exception {
        String merged = service.mergeFeatureFlags(
                "{\"featureFlags\":{\"butcher_pos.enabled\":true}}",
                new FeatureFlagsPatchRequest(null, false, null, null, null, null, null, null, null, null, null));

        assertThat(objectMapper.readTree(merged).path("featureFlags").path("butcher_pos.enabled").asBoolean())
                .isFalse();
    }

    @Test
    void mergeFeatureFlags_preservesExistingFlags() throws Exception {
        String merged = service.mergeFeatureFlags(
                "{\"featureFlags\":{\"pos_drafts.enabled\":true}}",
                new FeatureFlagsPatchRequest(null, true, null, null, null, null, null, null, null, null, null));

        assertThat(objectMapper.readTree(merged).path("featureFlags").path("butcher_pos.enabled").asBoolean())
                .isTrue();
        assertThat(objectMapper.readTree(merged).path("featureFlags").path("pos_drafts.enabled").asBoolean())
                .isTrue();
    }

    @Test
    void mergeFeatureFlags_enablesCashierCapabilities() throws Exception {
        String merged = service.mergeFeatureFlags(
                "{}",
                new FeatureFlagsPatchRequest(null, null, true, true, true, true, true, true, null, null, null));

        assertThat(objectMapper.readTree(merged).path("featureFlags").path("pos.cashier_price_edit").asBoolean())
                .isTrue();
        assertThat(objectMapper.readTree(merged).path("featureFlags").path("pos.cashier_create_product").asBoolean())
                .isTrue();
        assertThat(objectMapper.readTree(merged).path("featureFlags").path("pos.cashier_weighed_toggle").asBoolean())
                .isTrue();
        assertThat(objectMapper.readTree(merged).path("featureFlags").path("pos.cashier_add_photo").asBoolean())
                .isTrue();
        assertThat(objectMapper.readTree(merged).path("featureFlags").path("pos.cashier_order_pad").asBoolean())
                .isTrue();
        assertThat(objectMapper.readTree(merged).path("featureFlags").path("pos.cashier_order_confirm").asBoolean())
                .isTrue();
    }

    @Test
    void mergeFeatureFlags_enablesShiftPrefillOpeningFromLastClose() throws Exception {
        String merged = service.mergeFeatureFlags(
                "{}",
                new FeatureFlagsPatchRequest(null, null, null, null, null, null, null, null, true, null, null));

        assertThat(objectMapper.readTree(merged)
                        .path("featureFlags")
                        .path("shifts.prefill_opening_from_last_close")
                        .asBoolean())
                .isTrue();
    }

    @Test
    void mergeFeatureFlags_enablesHubAlerts() throws Exception {
        String merged = service.mergeFeatureFlags(
                "{}",
                new FeatureFlagsPatchRequest(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new zelisline.ub.tenancy.api.dto.HubAlertsFeatureFlagsPatch(true, false)));

        assertThat(objectMapper.readTree(merged)
                        .path("featureFlags")
                        .path("hub.alerts.beep_on_sale")
                        .asBoolean())
                .isTrue();
        assertThat(objectMapper.readTree(merged)
                        .path("featureFlags")
                        .path("hub.alerts.beep_on_supply")
                        .asBoolean())
                .isFalse();
    }

    @Test
    void mergeFeatureFlags_nullPatch_returnsCurrentSettings() {
        String current = "{\"featureFlags\":{}}";
        assertThat(service.mergeFeatureFlags(current, null)).isEqualTo(current);
    }
}
