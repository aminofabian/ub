package zelisline.ub.serviceops.api.dto;

/** A staff member who serves customers and can be chosen as the performer on a service line. */
public record ServicePerformerResponse(
        String staffProfileId,
        String displayName
) {
}
