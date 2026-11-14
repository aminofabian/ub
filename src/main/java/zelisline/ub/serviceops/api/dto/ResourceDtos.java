package zelisline.ub.serviceops.api.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class ResourceDtos {

    private ResourceDtos() {
    }

    public record CreateResourceRequest(
            @NotBlank @Size(max = 36) String branchId,
            @NotBlank @Size(max = 80) String name,
            /** CHAIR, STATION, BED, ROOM or EQUIPMENT. */
            @NotBlank @Pattern(regexp = "^[A-Z_]{2,24}$") String resourceType,
            @Min(0) @Max(240) Integer cleanupMinutes
    ) {
    }

    /** Every field is optional; only what is sent changes. */
    public record UpdateResourceRequest(
            @Size(min = 1, max = 80) String name,
            @Pattern(regexp = "^[A-Z_]{2,24}$") String resourceType,
            Boolean active,
            @Min(0) @Max(240) Integer cleanupMinutes,
            /** Out of use until this moment. Send {@code clearBlock} to put it back in use. */
            Instant blockedUntil,
            @Size(max = 191) String blockedReason,
            Boolean clearBlock
    ) {
    }

    public record ResourceResponse(
            String id,
            String branchId,
            String name,
            String resourceType,
            Integer stationNo,
            boolean active,
            int cleanupMinutes,
            Instant blockedUntil,
            String blockedReason,
            /** The visit using it now, if any. */
            String busyTicketId,
            /** Free again at this moment: being cleaned, or out of use. Null when free now or busy. */
            Instant freeAt
    ) {
    }

    /** Which kind of resource a service needs; null or blank for any. */
    public record SetServiceResourceTypeRequest(@Pattern(regexp = "^$|^[A-Z_]{2,24}$") String resourceType) {
    }

    public record ResourceListResponse(String branchId, List<ResourceResponse> resources) {
    }
}
