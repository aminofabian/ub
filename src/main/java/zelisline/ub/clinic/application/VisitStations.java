package zelisline.ub.clinic.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import zelisline.ub.clinic.domain.Prescription;
import zelisline.ub.posdraft.PosDraftConstants;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.TicketLineResponse;

/**
 * Where a visit is (ADR 0034, decision 2): a station is not stored, it is read off what the
 * visit is waiting for. Pure functions so the rule can be tested without a database.
 */
public final class VisitStations {

    public static final String BOOKED = "booked";
    public static final String WAITING = "waiting";
    public static final String CONSULT = "consult";
    public static final String LAB = "lab";
    public static final String DISPENSARY = "dispensary";
    public static final String PAY = "pay";
    public static final String DONE = "done";
    public static final String CANCELLED = "cancelled";

    private VisitStations() {
    }

    /**
     * @param performServiceIds services that are tests or procedures
     * @param prescriptionStatus the visit's prescription status, or null when it has none
     * @param prescriptionHasStocked whether that prescription has a medicine this shop stocks
     */
    public static String of(
            String ticketStatus,
            String ticketStage,
            List<TicketLineResponse> lines,
            Collection<String> performServiceIds,
            String prescriptionStatus,
            boolean prescriptionHasStocked
    ) {
        if (PosDraftConstants.STATUS_CANCELLED.equals(ticketStatus)) {
            return CANCELLED;
        }
        if (PosDraftConstants.STATUS_COMPLETED.equals(ticketStatus)) {
            return DONE;
        }
        if (PosDraftConstants.STAGE_BOOKED.equals(ticketStage)) {
            return BOOKED;
        }
        TicketLineResponse inService = null;
        TicketLineResponse nextWaiting = null;
        boolean anyDone = false;
        for (TicketLineResponse line : lines) {
            if (PosDraftConstants.STAGE_IN_SERVICE.equals(line.stage())) {
                if (inService == null) {
                    inService = line;
                }
            } else if (PosDraftConstants.LINE_DONE.equals(line.stage())) {
                anyDone = true;
            } else if (nextWaiting == null) {
                nextWaiting = line;
            }
        }
        if (inService != null) {
            return performServiceIds.contains(inService.serviceId()) ? LAB : CONSULT;
        }
        if (nextWaiting != null) {
            if (!anyDone) {
                return WAITING;
            }
            return performServiceIds.contains(nextWaiting.serviceId()) ? LAB : CONSULT;
        }
        // Every service is done. A prescription still being written or not yet prepared waits at
        // the dispensary; one the patient declined, or that names nothing this shop stocks, does not.
        if (Prescription.OPEN.equals(prescriptionStatus) && prescriptionHasStocked) {
            return DISPENSARY;
        }
        return PAY;
    }

    /** When the step the visit is in began. */
    public static Instant since(String station, Instant openedAt, List<TicketLineResponse> lines) {
        Instant latest = openedAt;
        for (TicketLineResponse line : lines) {
            if ((CONSULT.equals(station) || LAB.equals(station))
                    && PosDraftConstants.STAGE_IN_SERVICE.equals(line.stage())
                    && line.startedAt() != null) {
                return line.startedAt();
            }
            if (line.finishedAt() != null && (latest == null || line.finishedAt().isAfter(latest))) {
                latest = line.finishedAt();
            }
        }
        return WAITING.equals(station) ? openedAt : latest;
    }

    public static String firstName(String name) {
        if (name == null || name.isBlank()) {
            return "Patient";
        }
        String first = name.strip().split("\\s+")[0];
        return first.length() > 40 ? first.substring(0, 40) : first;
    }
}
