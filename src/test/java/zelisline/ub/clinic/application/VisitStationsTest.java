package zelisline.ub.clinic.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import zelisline.ub.clinic.domain.Prescription;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.TicketLineResponse;

class VisitStationsTest {

    private static final Set<String> TESTS = Set.of("svc-test");
    private static final Instant OPENED = Instant.parse("2026-11-21T07:00:00Z");

    private static TicketLineResponse line(String serviceId, String stage, Instant started, Instant finished) {
        return new TicketLineResponse(
                "line-" + serviceId + stage, serviceId, serviceId, BigDecimal.TEN, BigDecimal.TEN, List.of(),
                "staff-1", "Staff", null, null, stage, started, finished, 15, null, List.of());
    }

    private static String station(List<TicketLineResponse> lines, String rxStatus, boolean stocked) {
        return VisitStations.of("pending", "WAITING", lines, TESTS, rxStatus, stocked);
    }

    @Test
    void aVisitNobodyHasStartedIsWaiting() {
        assertEquals(VisitStations.WAITING, station(List.of(line("svc-consult", "WAITING", null, null)), null, false));
    }

    @Test
    void theServiceInProgressSaysWhereTheVisitIs() {
        assertEquals(VisitStations.CONSULT,
                station(List.of(line("svc-consult", "IN_SERVICE", OPENED, null)), null, false));
        assertEquals(VisitStations.LAB, station(List.of(
                line("svc-consult", "DONE", OPENED, OPENED), line("svc-test", "IN_SERVICE", OPENED, null)), null, false));
    }

    @Test
    void afterTheConsultationATestStillToDoWaitsAtTheLab() {
        assertEquals(VisitStations.LAB, station(List.of(
                line("svc-consult", "DONE", OPENED, OPENED), line("svc-test", "WAITING", null, null)), null, false));
        assertEquals(VisitStations.CONSULT, station(List.of(
                line("svc-test", "DONE", OPENED, OPENED), line("svc-consult", "WAITING", null, null)), null, false));
    }

    @Test
    void anUnpreparedPrescriptionWithSomethingInStockWaitsAtTheDispensary() {
        List<TicketLineResponse> done = List.of(line("svc-consult", "DONE", OPENED, OPENED));
        assertEquals(VisitStations.DISPENSARY, station(done, Prescription.OPEN, true));
        assertEquals(VisitStations.PAY, station(done, Prescription.OPEN, false));
        assertEquals(VisitStations.PAY, station(done, Prescription.PREPARED, true));
        assertEquals(VisitStations.PAY, station(done, Prescription.DECLINED, true));
        assertEquals(VisitStations.PAY, station(done, null, false));
    }

    @Test
    void closedAndBookedVisitsAreNotOnTheFloor() {
        List<TicketLineResponse> lines = List.of(line("svc-consult", "WAITING", null, null));
        assertEquals(VisitStations.DONE, VisitStations.of("completed", "READY_TO_PAY", lines, TESTS, null, false));
        assertEquals(VisitStations.CANCELLED, VisitStations.of("cancelled", "WAITING", lines, TESTS, null, false));
        assertEquals(VisitStations.BOOKED, VisitStations.of("pending", "BOOKED", lines, TESTS, null, false));
    }

    @Test
    void theClockStartsWhenTheStepDid() {
        Instant started = OPENED.plusSeconds(600);
        Instant finished = OPENED.plusSeconds(1200);
        assertEquals(OPENED, VisitStations.since(VisitStations.WAITING, OPENED,
                List.of(line("svc-consult", "WAITING", null, null))));
        assertEquals(started, VisitStations.since(VisitStations.CONSULT, OPENED,
                List.of(line("svc-consult", "IN_SERVICE", started, null))));
        assertEquals(finished, VisitStations.since(VisitStations.PAY, OPENED,
                List.of(line("svc-consult", "DONE", started, finished))));
    }

    @Test
    void theListShowsAFirstNameOnly() {
        assertEquals("Wanjiru", VisitStations.firstName("  Wanjiru   Kamau "));
        assertEquals("Patient", VisitStations.firstName(" "));
        assertEquals("Patient", VisitStations.firstName(null));
    }
}
