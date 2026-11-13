package zelisline.ub.posdraft.application;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import zelisline.ub.posdraft.PosDraftConstants;
import zelisline.ub.posdraft.domain.PosDraft;
import zelisline.ub.posdraft.domain.PosDraftLine;

/**
 * A rough "about how long" for each waiting ticket, from the planned minutes of the services.
 *
 * <p>The work ahead of a ticket is what is left on the customers in the chairs plus everything
 * queued before it; that work is shared between the people who can serve. It is an estimate for
 * telling a customer "about twenty minutes", not a promise: it does not know who is fast, and it
 * treats every available staff member as able to do every service.</p>
 */
final class QueueEstimator {

    private QueueEstimator() {
    }

    /**
     * @param tickets the floor, in queue order
     * @param servers how many customers can be served at once (chairs with someone available)
     * @return ticket id → minutes, for waiting tickets only; empty when no one can serve
     */
    static Map<String, Integer> estimate(
            List<PosDraft> tickets,
            Map<String, List<PosDraftLine>> linesByTicket,
            Map<String, Integer> minutesByService,
            int servers,
            Instant now
    ) {
        Map<String, Integer> out = new HashMap<>();
        if (servers <= 0) {
            return out;
        }
        long ahead = 0;
        for (PosDraft ticket : tickets) {
            if (PosDraftConstants.STAGE_IN_SERVICE.equals(ticket.getServiceStage())) {
                long planned = planned(linesByTicket.get(ticket.getId()), minutesByService);
                long elapsed = ticket.getServiceStartedAt() == null
                        ? 0
                        : Math.max(0, Duration.between(ticket.getServiceStartedAt(), now).toMinutes());
                ahead += Math.max(0, planned - elapsed);
            }
        }
        for (PosDraft ticket : tickets) {
            if (!PosDraftConstants.STAGE_WAITING.equals(ticket.getServiceStage())) {
                continue;
            }
            out.put(ticket.getId(), (int) Math.ceil(ahead / (double) servers));
            ahead += planned(linesByTicket.get(ticket.getId()), minutesByService);
        }
        return out;
    }

    private static long planned(List<PosDraftLine> lines, Map<String, Integer> minutesByService) {
        long total = 0;
        if (lines != null) {
            for (PosDraftLine line : lines) {
                total += minutesByService.getOrDefault(line.getServiceId(), 0);
            }
        }
        return total;
    }
}
