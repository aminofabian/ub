package zelisline.ub.integrations.whatsapp.domain;

/**
 * Lifecycle of a {@link WhatsAppChannelRoute}.
 *
 * <p>Mirrors the {@code *Statuses} holder pattern used across the codebase
 * (e.g. {@code WebOrderStatuses}, {@code GatewayStkPushStatuses}).
 */
public final class WhatsAppChannelRouteStatuses {

    /** Routing is live: inbound resolves to the shop, outbound may send. */
    public static final String ACTIVE = "active";

    /** Temporarily disabled: inbound is left unrouted, outbound is refused. */
    public static final String PAUSED = "paused";

    private WhatsAppChannelRouteStatuses() {
    }
}
