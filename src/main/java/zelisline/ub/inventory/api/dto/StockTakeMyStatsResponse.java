package zelisline.ub.inventory.api.dto;

import java.time.LocalDate;

public record StockTakeMyStatsResponse(
        String month,
        String timezone,
        LocalDate from,
        LocalDate to,
        int daysInPeriod,
        long itemsCounted,
        long sessionsStarted,
        long morningSessions,
        long eveningSessions,
        long dailyAuditSessions,
        long daysActive,
        int coveragePct,
        long approvedCounts,
        long escalatedCounts,
        long pendingReview,
        Integer cleanRatePct,
        int currentStreakDays,
        int bestStreakDays,
        long restockFlags,
        long notesLeft,
        String title,
        String highlight
) {}
