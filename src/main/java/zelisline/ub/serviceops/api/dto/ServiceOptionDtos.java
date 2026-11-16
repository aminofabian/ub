package zelisline.ub.serviceops.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** What a customer can choose on a service (ADR 0025). Limits mirror {@code ServiceOptions}. */
public final class ServiceOptionDtos {

    private ServiceOptionDtos() {
    }

    public record OptionInput(
            /** The id the option already has; leave out for a new one. */
            @Size(max = 12) String id,
            @NotBlank @Size(max = 40) String name,
            /** Added to the service's price when chosen; nothing means no extra charge. */
            @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal priceDelta,
            /** Added to the time the service takes when chosen. */
            @Min(0) @Max(600) Integer extraMinutes
    ) {
    }

    public record GroupInput(
            @Size(max = 12) String id,
            @NotBlank @Size(max = 40) String name,
            /** The visit cannot be paid until something here is chosen. */
            Boolean required,
            /** Several may be chosen (extras); otherwise one (a variant such as length). */
            Boolean multiple,
            @NotNull @Size(min = 1, max = 12) @Valid List<OptionInput> options
    ) {
    }

    public record SetOptionsRequest(@NotNull @Size(max = 6) @Valid List<GroupInput> groups) {
    }
}
