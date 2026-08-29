package zelisline.ub.onboarding.progress.application;

/**
 * Signals that setup progress may have changed for a business (catalog, supplier, sale, etc.).
 */
public record SetupProgressUpdatedEvent(String businessId) {
}
