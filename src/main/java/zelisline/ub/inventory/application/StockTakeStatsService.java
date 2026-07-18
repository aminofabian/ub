package zelisline.ub.inventory.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.api.dto.StockTakeMyStatsResponse;
import zelisline.ub.inventory.repository.StockTakeLineRepository;
import zelisline.ub.inventory.repository.StockTakeRestockItemRepository;
import zelisline.ub.inventory.repository.StockTakeSessionRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class StockTakeStatsService {

    private final StockTakeLineRepository stockTakeLineRepository;
    private final StockTakeSessionRepository stockTakeSessionRepository;
    private final StockTakeRestockItemRepository stockTakeRestockItemRepository;
    private final BusinessRepository businessRepository;

    @Value("${app.inventory.daily-stock-audit.zone:Africa/Nairobi}")
    private String fallbackZoneId;

    @Transactional(readOnly = true)
    public StockTakeMyStatsResponse myMonthStats(
            String businessId,
            String userId,
            YearMonth month
    ) {
        ZoneId zone = resolveZone(businessId);
        LocalDate today = LocalDate.now(zone);
        YearMonth target = month != null ? month : YearMonth.from(today);
        LocalDate from = target.atDay(1);
        LocalDate monthEnd = target.atEndOfMonth();
        LocalDate to = monthEnd.isAfter(today) ? today : monthEnd;
        if (to.isBefore(from)) {
            to = from;
        }

        long itemsCounted = stockTakeLineRepository.countSubmittedByUserBetween(
                businessId, userId, from, to
        );
        long daysActive = stockTakeLineRepository.countDistinctActiveDaysByUserBetween(
                businessId, userId, from, to
        );
        long sessionsStarted = stockTakeSessionRepository.countStartedByUserBetween(
                businessId, userId, from, to
        );
        long morningSessions = stockTakeSessionRepository.countStartedByUserAndTypeBetween(
                businessId,
                userId,
                from,
                to,
                InventoryConstants.STOCKTAKE_SESSION_TYPE_MORNING
        );
        long eveningSessions = stockTakeSessionRepository.countStartedByUserAndTypeBetween(
                businessId,
                userId,
                from,
                to,
                InventoryConstants.STOCKTAKE_SESSION_TYPE_EVENING
        );
        long dailyAuditSessions = stockTakeSessionRepository.countStartedByUserAndSourceBetween(
                businessId,
                userId,
                from,
                to,
                InventoryConstants.STOCKTAKE_SOURCE_DAILY_AUDIT
        );
        long approved = stockTakeLineRepository.countByReviewStatusForUserBetween(
                businessId,
                userId,
                from,
                to,
                InventoryConstants.DAILY_AUDIT_REVIEW_APPROVED
        );
        long escalated = stockTakeLineRepository.countByReviewStatusForUserBetween(
                businessId,
                userId,
                from,
                to,
                InventoryConstants.DAILY_AUDIT_REVIEW_ESCALATED
        );
        long pending = stockTakeLineRepository.countByReviewStatusForUserBetween(
                businessId,
                userId,
                from,
                to,
                InventoryConstants.DAILY_AUDIT_REVIEW_PENDING
        );
        long notesLeft = stockTakeLineRepository.countNotesByUserBetween(
                businessId, userId, from, to
        );

        Instant restockFrom = from.atStartOfDay(zone).toInstant();
        Instant restockTo = to.plusDays(1).atStartOfDay(zone).toInstant();
        long restockFlags = stockTakeRestockItemRepository.countAddedByUserBetween(
                businessId, userId, restockFrom, restockTo
        );

        int daysInPeriod = (int) (to.toEpochDay() - from.toEpochDay() + 1);
        int coveragePct = daysInPeriod <= 0
                ? 0
                : (int) Math.round((daysActive * 100.0) / daysInPeriod);

        long reviewed = approved + escalated;
        Integer cleanRatePct = reviewed == 0
                ? null
                : (int) Math.round((approved * 100.0) / reviewed);

        List<LocalDate> activeDates = stockTakeLineRepository.findActiveDatesByUserBetween(
                businessId, userId, from, to
        );
        int currentStreak = computeCurrentStreak(activeDates, today, to);
        int bestStreak = computeBestStreak(activeDates);

        String title = pickTitle(
                itemsCounted,
                coveragePct,
                cleanRatePct,
                currentStreak,
                restockFlags
        );
        String highlight = pickHighlight(
                itemsCounted,
                daysActive,
                currentStreak,
                cleanRatePct,
                approved,
                escalated,
                restockFlags
        );

        return new StockTakeMyStatsResponse(
                target.toString(),
                zone.getId(),
                from,
                to,
                daysInPeriod,
                itemsCounted,
                sessionsStarted,
                morningSessions,
                eveningSessions,
                dailyAuditSessions,
                daysActive,
                coveragePct,
                approved,
                escalated,
                pending,
                cleanRatePct,
                currentStreak,
                bestStreak,
                restockFlags,
                notesLeft,
                title,
                highlight
        );
    }

    private ZoneId resolveZone(String businessId) {
        String tz = businessRepository.findById(businessId)
                .map(Business::getTimezone)
                .filter(t -> t != null && !t.isBlank())
                .orElse(fallbackZoneId);
        try {
            return ZoneId.of(tz.trim());
        } catch (Exception ex) {
            return ZoneId.of("Africa/Nairobi");
        }
    }

    static int computeCurrentStreak(List<LocalDate> activeDatesDesc, LocalDate today, LocalDate periodTo) {
        if (activeDatesDesc == null || activeDatesDesc.isEmpty()) {
            return 0;
        }
        Set<LocalDate> set = new HashSet<>(activeDatesDesc);
        LocalDate cursor = periodTo.isBefore(today) ? periodTo : today;
        // Allow streak to start from yesterday if today has no count yet.
        if (!set.contains(cursor) && set.contains(cursor.minusDays(1))) {
            cursor = cursor.minusDays(1);
        }
        if (!set.contains(cursor)) {
            return 0;
        }
        int streak = 0;
        while (set.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    static int computeBestStreak(List<LocalDate> activeDatesDesc) {
        if (activeDatesDesc == null || activeDatesDesc.isEmpty()) {
            return 0;
        }
        List<LocalDate> sorted = activeDatesDesc.stream().sorted().distinct().toList();
        int best = 1;
        int run = 1;
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).equals(sorted.get(i - 1).plusDays(1))) {
                run++;
                best = Math.max(best, run);
            } else {
                run = 1;
            }
        }
        return best;
    }

    static String pickTitle(
            long itemsCounted,
            int coveragePct,
            Integer cleanRatePct,
            int currentStreak,
            long restockFlags
    ) {
        if (currentStreak >= 7) {
            return "Iron shelf";
        }
        if (cleanRatePct != null && cleanRatePct >= 95 && itemsCounted >= 20) {
            return "Sharp eye";
        }
        if (coveragePct >= 80 && itemsCounted >= 10) {
            return "Always on the floor";
        }
        if (restockFlags >= 10) {
            return "Restock radar";
        }
        if (itemsCounted >= 100) {
            return "Count machine";
        }
        if (itemsCounted >= 25) {
            return "Floor scout";
        }
        if (itemsCounted > 0) {
            return "Stock keeper";
        }
        return "Ready to count";
    }

    static String pickHighlight(
            long itemsCounted,
            long daysActive,
            int currentStreak,
            Integer cleanRatePct,
            long approved,
            long escalated,
            long restockFlags
    ) {
        if (itemsCounted == 0) {
            return "Start today’s count to unlock your month.";
        }
        if (currentStreak >= 3) {
            return currentStreak + "-day streak — keep showing up.";
        }
        if (cleanRatePct != null && cleanRatePct >= 90 && approved + escalated >= 5) {
            return cleanRatePct + "% clean approvals this month.";
        }
        if (restockFlags > 0 && restockFlags >= daysActive) {
            return restockFlags + " restock flags raised — shelves stay stocked.";
        }
        if (daysActive > 0) {
            return itemsCounted + " items counted across " + daysActive + " days.";
        }
        return itemsCounted + " items counted this month.";
    }
}
