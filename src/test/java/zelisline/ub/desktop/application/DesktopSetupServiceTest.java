package zelisline.ub.desktop.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.finance.application.LedgerBootstrapService;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.sales.application.ShiftService;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.tenancy.application.BusinessOnboardingSettingsService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * {@code isSetupRequired} routes the first launch to {@code /setup}. It must
 * key off the live Business row, not the {@code .initialized} marker — the
 * marker could be left behind by a first run that failed to commit, and
 * trusting it would strand the user on {@code /login} with no owner account.
 */
class DesktopSetupServiceTest {

    private final BusinessRepository businessRepository = mock(BusinessRepository.class);

    private DesktopSetupService service() {
        return new DesktopSetupService(
            businessRepository,
            mock(UserRepository.class),
            mock(RoleRepository.class),
            mock(BranchRepository.class),
            mock(PasswordEncoder.class),
            mock(BusinessOnboardingSettingsService.class),
            mock(CatalogBootstrapService.class),
            mock(LedgerBootstrapService.class),
            mock(ShiftService.class),
            mock(ShiftRepository.class),
            mock(DesktopInitializationService.class),
            mock(CloudSyncSession.class));
    }

    @Test
    void blankBusinessIdAlwaysRequiresSetup() {
        DesktopSetupService service = service();
        ReflectionTestUtils.setField(service, "desktopBusinessId", "");
        assertTrue(service.isSetupRequired());
    }

    @Test
    void noLiveBusinessRequiresSetup() {
        DesktopSetupService service = service();
        ReflectionTestUtils.setField(service, "desktopBusinessId", "biz-1");
        when(businessRepository.findByIdAndDeletedAtIsNull("biz-1"))
            .thenReturn(Optional.empty());
        assertTrue(service.isSetupRequired());
    }

    @Test
    void liveBusinessDoesNotRequireSetup() {
        DesktopSetupService service = service();
        ReflectionTestUtils.setField(service, "desktopBusinessId", "biz-1");
        when(businessRepository.findByIdAndDeletedAtIsNull("biz-1"))
            .thenReturn(Optional.of(mock(Business.class)));
        assertFalse(service.isSetupRequired());
    }
}
