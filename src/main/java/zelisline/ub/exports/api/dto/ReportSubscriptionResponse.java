package zelisline.ub.exports.api.dto;

import java.time.Instant;
import java.util.List;

import zelisline.ub.exports.application.ReportDeliveryService;
import zelisline.ub.exports.domain.ReportSubscription;

/** A saved report-delivery subscription. */
public record ReportSubscriptionResponse(
        String id,
        String reportKey,
        String format,
        String frequency,
        String sendTime,
        Integer dayOfWeek,
        Integer dayOfMonth,
        List<String> channels,
        String recipientEmail,
        String branchId,
        String categoryId,
        boolean active,
        String lastSentPeriod,
        Instant createdAt
) {
    public static ReportSubscriptionResponse of(ReportSubscription s) {
        return new ReportSubscriptionResponse(
                s.getId(), s.getReportKey(), s.getFormat(), s.getFrequency(), s.getSendTime(),
                s.getDayOfWeek(), s.getDayOfMonth(),
                List.copyOf(ReportDeliveryService.parseChannels(s.getChannels())),
                s.getRecipientEmail(), s.getBranchId(), s.getCategoryId(),
                s.isActive(), s.getLastSentPeriod(), s.getCreatedAt());
    }
}
