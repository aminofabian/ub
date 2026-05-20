package zelisline.ub.payments.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for creating or updating a tenant gateway configuration.
 * The raw credentials map is encrypted at rest by the service layer.
 */
public record GatewayConfigRequest(

        @NotBlank
        @Size(max = 32)
        String gatewayType,

        @NotBlank
        @Size(max = 100)
        String label,

        boolean isDefault,

        /** Gateway-specific key-value pairs (will be encrypted). */
        String credentialsJson,

        /** For MANUAL gateway type — Till/Paybill/Bank display data. */
        String displayInstructionsJson
) {
}
