package zelisline.ub.platform.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;

/** Request/response shapes for additional platform Meta app secrets. */
public final class PlatformWhatsAppAppSecretDtos {

    private PlatformWhatsAppAppSecretDtos() {
    }

    /**
     * @param id          row id
     * @param label       optional human label
     * @param fingerprint last 4 characters of the secret ({@code …ab12}) — never the full secret
     * @param createdAt   when it was added
     */
    public record AppSecretRow(String id, String label, String fingerprint, Instant createdAt) {
    }

    /** @param appSecret the secret value (write-only); {@code label} is optional */
    public record AddAppSecretRequest(@NotBlank String appSecret, String label) {
    }
}
