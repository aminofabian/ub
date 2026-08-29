package zelisline.ub.payroll.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import zelisline.ub.notifications.application.NotificationService;
import zelisline.ub.payroll.api.dto.PayAllRunRequest;
import zelisline.ub.payroll.api.dto.PayAllRunResponse;
import zelisline.ub.payroll.domain.PayrollAutomationMode;
import zelisline.ub.payroll.domain.PayrollAutomationSettings;
import zelisline.ub.payroll.repository.PayrollAutomationSettingsRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class PayrollAutomationService {

    private static final Logger log = LoggerFactory.getLogger(PayrollAutomationService.class);
    private static final String SYSTEM_ACTOR = "payroll-automation";

    private final PayrollAutomationSettingsRepository settingsRepository;
    private final PayrollAutomationSettingsService settingsService;
    private final PayrollService payrollService;
    private final NotificationService notificationService;
    private final BusinessRepository businessRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.payroll.automation.zone:Africa/Nairobi}")
    private String automationZone;

    public record AutomationRunSummary(int businesses, int autoPaid, int reminded, int failed) {
    }

    public AutomationRunSummary runScheduledPayroll() {
        List<PayrollAutomationSettings> enabled = settingsRepository.findByEnabledTrue();
        int businesses = 0;
        int autoPaid = 0;
        int reminded = 0;
        int failed = 0;

        for (PayrollAutomationSettings settings : enabled) {
            String businessId = settings.getBusinessId();
            try {
                Optional<String> slot = settingsService.claimRunSlotIfDue(businessId);
                if (slot.isEmpty()) {
                    continue;
                }
                businesses++;
                log.info("Payroll automation slot claimed: business={} slot={} mode={}",
                        businessId, slot.get(), settings.getAutomationMode());

                if (PayrollAutomationMode.REMIND.equals(settings.getAutomationMode())) {
                    remindOwners(businessId, settings);
                    reminded++;
                } else {
                    PayAllRunResponse result = autoPayBusiness(businessId, settings);
                    if (result.paidCount() > 0 || result.skippedCount() > 0) {
                        autoPaid++;
                    }
                    if (!result.failures().isEmpty()) {
                        failed++;
                    }
                }
            } catch (Exception ex) {
                failed++;
                log.error("Payroll automation failed for business {}", businessId, ex);
            }
        }

        return new AutomationRunSummary(businesses, autoPaid, reminded, failed);
    }

    private PayAllRunResponse autoPayBusiness(String businessId, PayrollAutomationSettings settings) {
        ZoneId zone = ZoneId.of(automationZone);
        LocalDate today = LocalDate.now(zone);
        int year = today.getYear();
        int month = today.getMonthValue();

        return payrollService.payAll(
                businessId,
                new PayAllRunRequest(
                        year,
                        month,
                        settings.isApplyStatutory(),
                        settings.isPostExpense(),
                        settings.getPaymentMethod(),
                        settings.getBranchId()
                ),
                SYSTEM_ACTOR
        );
    }

    private void remindOwners(String businessId, PayrollAutomationSettings settings) {
        ZoneId zone = ZoneId.of(automationZone);
        LocalDate today = LocalDate.now(zone);
        YearMonth period = YearMonth.from(today);
        String dedupeKey = "payroll_due:" + period + ":" + settings.getPayDayOfMonth();

        Business business = businessRepository.findById(businessId).orElse(null);
        String shopName = business != null && business.getName() != null
                ? business.getName().trim()
                : "Your shop";

        var preview = payrollService.previewRun(
                businessId,
                period.getYear(),
                period.getMonthValue(),
                settings.getBranchId(),
                settings.isApplyStatutory()
        );
        long pending = preview.stream().filter(row -> !row.alreadyPaid()).count();

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("title", "Payroll due — " + period.getMonth() + "/" + period.getYear());
        payload.put("body", pending + " staff pending for " + shopName + ". Review and mark paid in Payroll.");
        payload.put("path", "/payroll");
        payload.put("pendingCount", pending);
        payload.put("year", period.getYear());
        payload.put("month", period.getMonthValue());

        notificationService.tryInsertDedupe(
                businessId,
                "payroll_due",
                dedupeKey,
                "operational",
                "HIGH",
                payload.toString()
        );
    }
}
