package zelisline.ub.tenancy.api.dto;

import jakarta.validation.constraints.NotNull;

/** Whether visitors who open this host should be sent to the shop's main address. */
public record SetDomainRedirectRequest(@NotNull Boolean enabled) {
}
