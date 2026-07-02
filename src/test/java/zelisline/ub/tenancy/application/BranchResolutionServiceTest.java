package zelisline.ub.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import zelisline.ub.identity.application.RequestPermissionService;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.tenancy.repository.BranchRepository;

@ExtendWith(MockitoExtension.class)
class BranchResolutionServiceTest {

    @Mock
    private BranchRepository branchRepository;

    @Mock
    private FeatureFlagService featureFlagService;

    @Mock
    private RequestPermissionService requestPermissionService;

    @Mock
    private RoleRepository roleRepository;

    @InjectMocks
    private BranchResolutionService branchResolutionService;

    @Test
    void butcherCashierIsBranchLocked() {
        Role role = new Role();
        role.setRoleKey("butcher_cashier");
        when(roleRepository.findByIdAndDeletedAtIsNull("role-butcher")).thenReturn(Optional.of(role));

        assertThat(branchResolutionService.isBranchLockedRole("role-butcher")).isTrue();
    }

    @Test
    void cashierIsBranchLocked() {
        Role role = new Role();
        role.setRoleKey("cashier");
        when(roleRepository.findByIdAndDeletedAtIsNull("role-cashier")).thenReturn(Optional.of(role));

        assertThat(branchResolutionService.isBranchLockedRole("role-cashier")).isTrue();
    }

    @Test
    void adminIsNotBranchLocked() {
        Role role = new Role();
        role.setRoleKey("admin");
        when(roleRepository.findByIdAndDeletedAtIsNull("role-admin")).thenReturn(Optional.of(role));

        assertThat(branchResolutionService.isBranchLockedRole("role-admin")).isFalse();
    }

    @Test
    void nullRoleIsNotBranchLocked() {
        assertThat(branchResolutionService.isBranchLockedRole(null)).isFalse();
        assertThat(branchResolutionService.isBranchLockedRole("")).isFalse();
    }
}
