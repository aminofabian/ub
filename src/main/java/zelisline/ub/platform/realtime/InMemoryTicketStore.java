package zelisline.ub.platform.realtime;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory ticket store for the {@code local} and {@code hybrid} Spring profiles
 * where Redis is not available.
 *
 * <p>Thread-safe via {@link ConcurrentHashMap}. Tickets are evicted lazily on
 * validation (TTL check in {@link RealtimeTicketService#validateAndConsume}).
 */
final class InMemoryTicketStore {

    private static final ConcurrentMap<String, TicketRecord> store = new ConcurrentHashMap<>();

    private InMemoryTicketStore() {}

    static void put(String ticketHash, TicketRecord record) {
        store.put(ticketHash, record);
    }

    static TicketRecord remove(String ticketHash) {
        return store.remove(ticketHash);
    }

    /** For observability — exposed via actuator or debug endpoint. */
    static int size() {
        return store.size();
    }
}
