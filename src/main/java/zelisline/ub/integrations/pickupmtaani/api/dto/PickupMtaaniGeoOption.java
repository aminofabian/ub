package zelisline.ub.integrations.pickupmtaani.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A zone, area, location, or agent returned by the proxied origin search. The
 * parent ids are present only when the upstream row carries them.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PickupMtaaniGeoOption(
        long id,
        String name,
        Long zoneId,
        Long areaId,
        Long locationId
) {
}
