package zelisline.ub.identity.application;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.repository.PermissionRepository;

/**
 * Request-scoped permission cache (PHASE_1_PLAN.md §2.2).
 *
 * <p>Multiple {@code hasPermission(null, '…')} checks in a single HTTP request
 * share one JDBC round-trip per distinct {@code roleId}.
 */
@Component
@RequestScope
@RequiredArgsConstructor
public class RequestPermissionService {

    private final PermissionRepository permissionRepository;

    private final Map<String, Set<String>> cache = new HashMap<>();

    public boolean hasPermission(String roleId, String permissionKey) {
        if (roleId == null || roleId.isBlank() || permissionKey == null || permissionKey.isBlank()) {
            return false;
        }
        String key = permissionKey.trim().toLowerCase();
        Set<String> keys = cache.computeIfAbsent(roleId, this::loadKeys);
        return keys.contains(key);
    }

    private Set<String> loadKeys(String roleId) {
        return new HashSet<>(permissionRepository.findPermissionKeysByRoleId(roleId));
    }
}
