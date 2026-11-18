package zelisline.ub.restaurant.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import zelisline.ub.restaurant.api.dto.OrderDtos.OrderCard;
import zelisline.ub.restaurant.api.dto.RoomDtos.AccommodationReportResponse;
import zelisline.ub.restaurant.api.dto.RoomDtos.FrontDeskResponse;
import zelisline.ub.restaurant.api.dto.RoomDtos.RoomWithStays;
import zelisline.ub.serviceops.application.ServiceResourceService;

/**
 * The accommodation dashboard and its figures (ADR 0030), kept apart from restaurant reporting. The
 * "today" snapshot — who is in, arriving, leaving, and which rooms need attention — reads the live
 * front desk; the money and occupancy figures (ADR, RevPAR, room-nights sold) read the settled ROOM
 * sale lines, so accommodation revenue stays a category of its own.
 */
@Service
@RequiredArgsConstructor
public class AccommodationReportService {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final int MAX_DAYS = 366;

    private final DineInService dineInService;
    private final ServiceResourceService resourceService;
    private final RestaurantGate gate;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public AccommodationReportResponse report(String businessId, String branchId, LocalDate from, LocalDate to) {
        gate.requireEnabled(businessId);
        if (from == null || to == null || to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a date range");
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That range is too long");
        }

        // Today's snapshot, from the live front desk.
        String today = LocalDate.now(NAIROBI).toString();
        FrontDeskResponse desk = dineInService.frontDesk(businessId, branchId);
        int totalRooms = 0;
        int occupied = 0;
        int free = 0;
        int arriving = 0;
        int leaving = 0;
        int dirty = 0;
        int maintenance = 0;
        BigDecimal outstanding = BigDecimal.ZERO;
        for (RoomWithStays entry : desk.rooms()) {
            totalRooms++;
            String hk = entry.room().housekeepingStatus();
            if (ServiceResourceService.HOUSEKEEPING_MAINTENANCE.equals(hk)) {
                maintenance++;
            } else if (ServiceResourceService.HOUSEKEEPING_DIRTY.equals(hk)
                    || "CLEANING".equals(hk)) {
                dirty++;
            }
            OrderCard here = null;
            boolean soon = false;
            for (OrderCard stay : entry.stays()) {
                if (stay.settled()) {
                    continue;
                }
                if (!stay.booked()) {
                    here = stay;
                } else if (sameDay(stay.scheduledStart(), today)) {
                    soon = true;
                }
            }
            if (here != null) {
                occupied++;
                outstanding = outstanding.add(here.unsettled() == null ? BigDecimal.ZERO : here.unsettled());
                if (sameDay(here.scheduledEnd(), today)) {
                    leaving++;
                }
            } else if (soon) {
                arriving++;
            } else if (entry.room().readyToLet()) {
                // Free = nobody in, and the room can take a guest tonight (not dirty, out of use, or
                // held by a later booking). Matches the front desk's tally exactly.
                free++;
            }
        }
        // Stays whose room is gone still owe money; count their balance, not against a room.
        for (OrderCard stay : desk.otherStays()) {
            if (!stay.settled() && !stay.booked()) {
                outstanding = outstanding.add(stay.unsettled() == null ? BigDecimal.ZERO : stay.unsettled());
            }
        }

        // The period's money and nights, from settled ROOM sale lines (accommodation revenue only).
        Instant start = from.atStartOfDay(NAIROBI).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(NAIROBI).toInstant();
        Object[] totals = roomLineTotals(businessId, branchId, start, end);
        long nightsSold = totals[0] == null ? 0 : ((Number) totals[0]).longValue();
        BigDecimal revenue = totals[1] == null ? BigDecimal.ZERO
                : new BigDecimal(totals[1].toString()).setScale(2, RoundingMode.HALF_UP);

        long availableRoomNights = (long) totalRooms * days;
        BigDecimal adr = nightsSold == 0 ? BigDecimal.ZERO
                : revenue.divide(BigDecimal.valueOf(nightsSold), 2, RoundingMode.HALF_UP);
        BigDecimal revpar = availableRoomNights == 0 ? BigDecimal.ZERO
                : revenue.divide(BigDecimal.valueOf(availableRoomNights), 2, RoundingMode.HALF_UP);
        BigDecimal occupancy = availableRoomNights == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(nightsSold).divide(BigDecimal.valueOf(availableRoomNights), 4, RoundingMode.HALF_UP);

        return new AccommodationReportResponse(branchId, from, to, totalRooms, occupied, free, arriving, leaving,
                dirty, maintenance, outstanding.setScale(2, RoundingMode.HALF_UP), nightsSold, revenue, adr, revpar,
                occupancy);
    }

    /** {@code [nights, revenue]} of settled room-night lines for a branch over a period. */
    private Object[] roomLineTotals(String businessId, String branchId, Instant start, Instant end) {
        List<?> rows = entityManager.createNativeQuery("""
                        SELECT SUM(si.quantity), SUM(si.line_total)
                          FROM sale_items si
                          JOIN sales s ON s.id = si.sale_id
                         WHERE s.business_id = :businessId
                           AND s.branch_id = :branchId
                           AND s.status <> 'voided'
                           AND si.line_kind = 'ROOM'
                           AND s.sold_at >= :fromTs AND s.sold_at < :toTs
                        """)
                .setParameter("businessId", businessId)
                .setParameter("branchId", branchId)
                .setParameter("fromTs", start)
                .setParameter("toTs", end)
                .getResultList();
        if (rows.isEmpty()) {
            return new Object[] {0L, BigDecimal.ZERO};
        }
        return (Object[]) rows.get(0);
    }

    private static boolean sameDay(Instant iso, String today) {
        return iso != null && iso.atZone(NAIROBI).toLocalDate().toString().equals(today);
    }
}
