package zelisline.ub.notifications.application;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.finance.api.dto.CashSurplusResponse;
import zelisline.ub.finance.application.ProfitPocketService;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.payments.domain.ProfitPocketSettings;
import zelisline.ub.payments.repository.ProfitPocketSettingsRepository;

/**
 * Friday Pocket reminder — nudge owners to park cash surplus before the weekend
 * (in-app + per-finance-user WhatsApp/SMS when phone is on file).
 */
@Service
@RequiredArgsConstructor
public class FridayPocketReminderService {

    private static final Logger log = LoggerFactory.getLogger(FridayPocketReminderService.class);

    public static final String TYPE_FRIDAY_POCKET = "profit_pocket.friday_reminder";
    private static final String FINANCE_WRITE = "finance.expenses.write";

    private static final BigDecimal EPS = new BigDecimal("0.01");

    private final ProfitPocketSettingsRepository settingsRepository;
    private final ProfitPocketService profitPocketService;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.notifications.profit-pocket.friday.zone:Africa/Nairobi}")
    private String zoneId;

    public void enqueueFridayReminders() {
        LocalDate today = LocalDate.now(ZoneId.of(zoneId));
        if (today.getDayOfWeek() != DayOfWeek.FRIDAY) {
            return;
        }
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);
        List<ProfitPocketSettings> candidates = settingsRepository.findFridayReminderCandidates();
        for (ProfitPocketSettings settings : candidates) {
            try {
                emitIfNeeded(settings, weekStart, today);
            } catch (Exception ex) {
                log.warn("Friday pocket reminder skipped businessId={}", settings.getBusinessId(), ex);
            }
        }
    }

    void emitIfNeeded(ProfitPocketSettings settings, LocalDate from, LocalDate to) {
        if (!isConfigured(settings)) {
            return;
        }
        CashSurplusResponse surplus =
                profitPocketService.cashSurplus(settings.getBusinessId(), from, to, null);
        BigDecimal suggested = surplus.suggestedPocket();
        if (suggested == null || suggested.compareTo(EPS) <= 0) {
            return;
        }
        String businessId = settings.getBusinessId();
        String body =
                "Pocket KES " + suggested.toPlainString()
                        + " before the weekend"
                        + (surplus.destinationSummary() != null
                                ? " → " + surplus.destinationSummary()
                                : "")
                        + ".";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", from.toString());
        payload.put("to", to.toString());
        payload.put("suggestedPocket", suggested.toPlainString());
        payload.put("grossProfit", surplus.grossProfit() == null ? null : surplus.grossProfit().toPlainString());
        payload.put("destinationSummary", surplus.destinationSummary());
        payload.put("actionUrl", "/business?pocket=1");
        payload.put("title", "Friday Pocket");
        payload.put("body", body);
        String json = toJson(payload);

        // Business-wide inbox (+ email fan-out via StaffEmailFanoutService).
        String businessDedupe = "profit_pocket.friday:" + businessId + ":" + to;
        notificationService.tryInsertDedupe(
                businessId,
                TYPE_FRIDAY_POCKET,
                businessDedupe,
                "operational",
                "MEDIUM",
                json);

        // Per-user rows so WhatsApp / SMS channels can target owner phones.
        List<String> userIds = userRepository.findIdsWithPermission(businessId, FINANCE_WRITE);
        for (String userId : userIds) {
            String userDedupe = businessDedupe + ":" + userId;
            notificationService.tryInsertDedupeForUser(
                    businessId,
                    userId,
                    TYPE_FRIDAY_POCKET,
                    userDedupe,
                    "operational",
                    "MEDIUM",
                    json);
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isConfigured(ProfitPocketSettings s) {
        if (s.getDestinationType() == null || s.getDestinationType().isBlank()) {
            return false;
        }
        return switch (s.getDestinationType()) {
            case ProfitPocketSettings.TYPE_BANK ->
                    hasText(s.getDestinationAccount())
                            && hasText(s.getDestinationBankName())
                            && hasText(s.getDestinationPaybill());
            case ProfitPocketSettings.TYPE_MPESA_PHONE, ProfitPocketSettings.TYPE_TILL ->
                    hasText(s.getDestinationAccount());
            case ProfitPocketSettings.TYPE_PAYBILL ->
                    hasText(s.getDestinationPaybill()) && hasText(s.getDestinationPaybillAccount());
            default -> false;
        };
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
