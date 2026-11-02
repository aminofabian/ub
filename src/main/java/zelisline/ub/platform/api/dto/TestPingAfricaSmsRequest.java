package zelisline.ub.platform.api.dto;

import jakarta.validation.constraints.NotBlank;

/** One-off Ping Africa SMS from Super Admin → Platform integrations. */
public record TestPingAfricaSmsRequest(@NotBlank String phone) {
}
