package zelisline.ub.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import zelisline.ub.identity.repository.PermissionRepository;

/**
 * Slice 2 DoD (PHASE_1_PLAN.md §2.5): permissions are read once per request for
 * a given role — here validated by counting repository calls on the
 * request-scoped service instance (same behaviour as under Spring's
 * {@code @RequestScope} proxy, which delegates to one target per HTTP request).
 */
@ExtendWith(MockitoExtension.class)
class RequestPermissionServiceTest {

    @Mock
    private PermissionRepository permissionRepository;

    @InjectMocks
    private RequestPermissionService requestPermissionService;

    @Test
    void secondPermissionCheckReusesFirstLoad() {
        given(permissionRepository.findPermissionKeysByRoleId("role-1"))
                .willReturn(List.of("users.list", "catalog.items.read"));

        assertThat(requestPermissionService.hasPermission("role-1", "users.list")).isTrue();
        assertThat(requestPermissionService.hasPermission("role-1", "catalog.items.read")).isTrue();
        assertThat(requestPermissionService.hasPermission("role-1", "users.create")).isFalse();

        verify(permissionRepository, times(1)).findPermissionKeysByRoleId("role-1");
    }

    @Test
    void distinctRolesLoadIndependently() {
        given(permissionRepository.findPermissionKeysByRoleId("role-a"))
                .willReturn(List.of("users.list"));
        given(permissionRepository.findPermissionKeysByRoleId("role-b"))
                .willReturn(List.of("catalog.items.read"));

        assertThat(requestPermissionService.hasPermission("role-a", "users.list")).isTrue();
        assertThat(requestPermissionService.hasPermission("role-b", "catalog.items.read")).isTrue();

        verify(permissionRepository, times(1)).findPermissionKeysByRoleId("role-a");
        verify(permissionRepository, times(1)).findPermissionKeysByRoleId("role-b");
    }
}
