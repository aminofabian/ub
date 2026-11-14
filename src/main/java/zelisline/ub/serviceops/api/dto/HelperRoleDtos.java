package zelisline.ub.serviceops.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class HelperRoleDtos {

    private HelperRoleDtos() {
    }

    /** A usual helper on a service: what they are called and what they are paid for it. */
    public record SaveHelperRoleRequest(
            @NotBlank @Size(max = 60) String name,
            /** {@code PERCENT} of the service's price, or a set {@code AMOUNT} in whole shillings. */
            @NotBlank @Pattern(regexp = "^(PERCENT|AMOUNT)$") String payKind,
            /** 1 to 99 for a percentage; whole shillings, at least 1, for an amount. */
            @NotNull @DecimalMin("1") @Digits(integer = 9, fraction = 2) BigDecimal payValue
    ) {
    }

    public record HelperRoleResponse(String id, String name, String payKind, BigDecimal payValue) {
    }

    public record HelperRoleListResponse(List<HelperRoleResponse> roles) {
    }
}
