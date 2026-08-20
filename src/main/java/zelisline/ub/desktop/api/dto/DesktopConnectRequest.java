package zelisline.ub.desktop.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * "Sign in with my online shop" payload for the desktop first-run flow.
 *
 * <p>The credentials are used <em>once</em> to authenticate against the cloud
 * and pull the shop's master data; the local install keeps only a local user
 * and a sync mapping file (never the password).
 */
public record DesktopConnectRequest(
        String origin,
        @NotBlank @Email @Size(max = 191) String email,
        @NotBlank @Size(max = 2048) String password
) {

    /** Cloud base URL with trailing slashes stripped; defaults to the product origin. */
    public String normalizedOrigin() {
        if (origin == null || origin.isBlank()) {
            return "https://kiosk.zelisline.com";
        }
        String trimmed = origin.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
