package zelisline.ub.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import zelisline.ub.tenancy.api.dto.TenantConfigBundle;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@ExtendWith(MockitoExtension.class)
class FeatureFlagServiceTest {

    @Mock
    private BusinessRepository businessRepository;

    @Mock
    private StorefrontSettingsService storefrontSettingsService;

    @InjectMocks
    private FeatureFlagService featureFlagService;

    @Test
    void butcherPosFlag_absent_returnsFalse() {
        Business business = new Business();
        business.setSettings("{}");
        when(businessRepository.findById("biz-1")).thenReturn(Optional.of(business));
        when(storefrontSettingsService.readTenantConfig("{}", ""))
                .thenReturn(TenantConfigBundle.defaults("Test"));

        assertThat(featureFlagService.isButcherPosEnabled("biz-1")).isFalse();
    }

    @Test
    void butcherPosFlag_true_returnsTrue() {
        Business business = new Business();
        business.setSettings("{\"featureFlags\":{\"butcher_pos.enabled\":true}}");
        when(businessRepository.findById("biz-2")).thenReturn(Optional.of(business));
        when(storefrontSettingsService.readTenantConfig(business.getSettings(), ""))
                .thenReturn(new TenantConfigBundle(null, null, Map.of("butcher_pos.enabled", true)));

        assertThat(featureFlagService.isButcherPosEnabled("biz-2")).isTrue();
    }

    @Test
    void businessMissing_returnsFalse() {
        when(businessRepository.findById("missing")).thenReturn(Optional.empty());

        assertThat(featureFlagService.isButcherPosEnabled("missing")).isFalse();
    }
}
