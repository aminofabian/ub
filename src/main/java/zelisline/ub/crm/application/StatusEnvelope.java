package zelisline.ub.crm.application;

/**
 * Channel-agnostic delivery-status update for {@link CrmDeliveryStatusService}.
 *
 * <p>Keeps the CRM independent of the transport ({@code messaging}) package so the dependency
 * stays {@code messaging -> crm}.
 */
public record StatusEnvelope(
        String wamid,
        String status,
        String errorDetail
) {
}
