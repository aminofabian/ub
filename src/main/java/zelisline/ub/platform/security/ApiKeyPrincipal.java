package zelisline.ub.platform.security;

import java.util.Set;

/**
 * Machine principal for {@code X-API-Key} / {@code Bearer kpos_*} authentication (Phase 8).
 * Scopes are stored as integration permission keys (same strings as {@code hasPermission}).
 */
public record ApiKeyPrincipal(String apiKeyId, String businessId, Set<String> scopes) {
}
