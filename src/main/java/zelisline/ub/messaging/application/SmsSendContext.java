package zelisline.ub.messaging.application;

/**
 * Optional facts a caller knows about the text it is about to send, read by
 * {@link SmsMessageLogService} when it records the message. Thread-bound and scoped
 * with try-with-resources:
 *
 * <pre>
 * try (SmsSendContext.Scope ignored = SmsSendContext.promotional(customerId, campaignId)) {
 *     smsMessagingClient.sendText(...);
 * }
 * </pre>
 *
 * Without a scope the message is logged as transactional and the customer is looked up
 * by phone number.
 */
public final class SmsSendContext {

    private static final ThreadLocal<Values> CURRENT = new ThreadLocal<>();

    private SmsSendContext() {
    }

    public record Values(String customerId, String campaignId, boolean promotional) {
    }

    public static Scope promotional(String customerId, String campaignId) {
        return open(new Values(customerId, campaignId, true));
    }

    public static Scope transactional(String customerId) {
        return open(new Values(customerId, null, false));
    }

    static Values current() {
        return CURRENT.get();
    }

    private static Scope open(Values values) {
        Values previous = CURRENT.get();
        CURRENT.set(values);
        return new Scope(previous);
    }

    public static final class Scope implements AutoCloseable {
        private final Values previous;

        private Scope(Values previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
