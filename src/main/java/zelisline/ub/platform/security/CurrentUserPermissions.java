package zelisline.ub.platform.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * Programmatic counterpart to {@code @PreAuthorize("hasPermission(null, '…')")}.
 *
 * <p>Needed when the permission a request requires depends on its <em>payload</em>
 * rather than its route — e.g. a store-room movement needs {@code inventory.write}
 * when it changes real stock but only {@code catalog.items.write} when it adjusts
 * the local register. Evaluates through the exact same
 * {@link PermissionEvaluator} as SpEL, so delegated roles and integration-key
 * scopes behave identically to an annotation.
 */
@Component
@RequiredArgsConstructor
public class CurrentUserPermissions {

    private final PermissionEvaluator permissionEvaluator;

    public boolean has(String permissionKey) {
        if (permissionKey == null || permissionKey.isBlank()) {
            return false;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && permissionEvaluator.hasPermission(authentication, null, permissionKey);
    }

    /** @throws ResponseStatusException 403 when the caller lacks {@code permissionKey}. */
    public void require(String permissionKey) {
        if (!has(permissionKey)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing permission: " + permissionKey);
        }
    }

    /**
     * @throws ResponseStatusException 403 when the caller holds none of the keys.
     *     Used where two different roles are equally entitled to the same action —
     *     e.g. an owner ({@code catalog.items.write}) or a delegated stock manager
     *     ({@code inventory.write}) adjusting the same back-room line.
     */
    public void requireAny(String... permissionKeys) {
        for (String key : permissionKeys) {
            if (has(key)) {
                return;
            }
        }
        throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Missing permission: " + String.join(" or ", permissionKeys));
    }
}
