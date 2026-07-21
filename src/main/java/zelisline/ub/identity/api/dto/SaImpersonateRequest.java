package zelisline.ub.identity.api.dto;

/**
 * Optional body for {@code POST /super-admin/businesses/{id}/impersonate}.
 * When {@code userId} is omitted, the oldest active owner is used.
 */
public record SaImpersonateRequest(String userId) {
}
