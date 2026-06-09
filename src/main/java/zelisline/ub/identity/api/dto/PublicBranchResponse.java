package zelisline.ub.identity.api.dto;

/**
 * Minimal branch projection exposed on the public login surface so cashiers can
 * pick their branch by name instead of pasting a UUID. Only id + name are
 * revealed — no address, settings, or other tenant data.
 */
public record PublicBranchResponse(String id, String name) {
}
