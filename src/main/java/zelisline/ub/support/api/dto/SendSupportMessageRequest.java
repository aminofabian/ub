package zelisline.ub.support.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendSupportMessageRequest(
        @NotBlank @Size(max = 4000) String body,
        /** Optional display name — used by guest senders to self-identify. */
        @Size(max = 120) String guestName
) {}
