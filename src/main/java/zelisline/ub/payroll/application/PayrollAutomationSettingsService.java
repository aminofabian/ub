package zelisline.ub.payroll.application;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.finance.FinanceConstants;
import zelisline.ub.payroll.api.dto.PayrollAutomationSettingsRequest;
import zelisline.ub.payroll.api.dto.PayrollAutomationSettingsResponse;
import zelisline.ub.payroll.domain.PayrollAutomationMode;
import zelisline.ub.payroll.domain.PayrollAutomationSettings;
import zelisline.ub.payroll.repository.PayrollAutomationSettingsRepository;
import zelisline.ub.payments.application.SupplierAutoPayTimes;

@Service
@RequiredArgsConstructor
public class PayrollAutomationSettingsService {

    private static final DateTimeFormatter SLOT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final List<String> DEFAULT_TIMES = List.of("09:00");

    private final PayrollAutomationSettingsRepository settingsRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.payroll.automation.zone:Africa/Nairobi}")
    private String automationZone;

    @Transactional(readOnly = true)
    public PayrollAutomationSettingsResponse getSettings(String businessId) {
        return toResponse(settingsRepository.findById(businessId)
                .orElseGet(() -> PayrollAutomationSettings.disabledFor(businessId)));
    }

    @Transactional
    public PayrollAutomationSettingsResponse updateSettings(
            String businessId,
            PayrollAutomationSettingsRequest request
    ) {
        PayrollAutomationSettings settings = settingsRepository.findById(businessId)
                .orElseGet(() -> PayrollAutomationSettings.disabledFor(businessId));

        if (request.enabled() != null) {
            settings.setEnabled(request.enabled());
        }
        if (request.automationMode() != null && !request.automationMode().isBlank()) {
            settings.setAutomationMode(normalizeMode(request.automationMode()));
        }
        if (request.payDayOfMonth() != null) {
            settings.setPayDayOfMonth(clampPayDay(request.payDayOfMonth()));
        }
        if (request.autoPayTimes() != null) {
            List<String> times = SupplierAutoPayTimes.requireValid(request.autoPayTimes());
            settings.setAutoPayTimesJson(SupplierAutoPayTimes.toJson(times, objectMapper));
        } else if (settings.getAutoPayTimesJson() == null || settings.getAutoPayTimesJson().isBlank()) {
            settings.setAutoPayTimesJson(SupplierAutoPayTimes.toJson(DEFAULT_TIMES, objectMapper));
        }
        if (request.applyStatutory() != null) {
            settings.setApplyStatutory(request.applyStatutory());
        }
        if (request.postExpense() != null) {
            settings.setPostExpense(request.postExpense());
        }
        if (request.paymentMethod() != null) {
            settings.setPaymentMethod(normalizePaymentMethod(request.paymentMethod()));
        }
        if (request.branchId() != null) {
            settings.setBranchId(blankToNull(request.branchId()));
        }

        settingsRepository.save(settings);
        return toResponse(settings);
    }

    @Transactional(readOnly = true)
    public List<PayrollAutomationSettings> listEnabledSettings() {
        return settingsRepository.findByEnabledTrue();
    }

    /**
     * Claims a slot when today is the configured pay day and the current minute matches a run time.
     */
    @Transactional
    public Optional<String> claimRunSlotIfDue(String businessId) {
        PayrollAutomationSettings settings = settingsRepository.findById(businessId).orElse(null);
        if (settings == null || !settings.isEnabled()) {
            return Optional.empty();
        }
        ZoneId zone = ZoneId.of(automationZone);
        LocalDateTime now = LocalDateTime.now(zone).withSecond(0).withNano(0);
        int effectivePayDay = effectivePayDay(settings.getPayDayOfMonth(), now.toLocalDate());
        if (now.getDayOfMonth() != effectivePayDay) {
            return Optional.empty();
        }
        List<String> times = SupplierAutoPayTimes.parseOrDefault(settings.getAutoPayTimesJson(), objectMapper);
        if (!SupplierAutoPayTimes.matchesMinute(times, now.toLocalTime())) {
            return Optional.empty();
        }
        String slot = now.format(SLOT);
        if (slot.equals(settings.getAutoPayLastRunSlot())) {
            return Optional.empty();
        }
        settings.setAutoPayLastRunSlot(slot);
        settingsRepository.save(settings);
        return Optional.of(slot);
    }

    static int effectivePayDay(int configuredDay, java.time.LocalDate date) {
        int clamped = clampPayDay(configuredDay);
        int lastDay = YearMonth.from(date).lengthOfMonth();
        return Math.min(clamped, lastDay);
    }

    private static int clampPayDay(int day) {
        return Math.max(1, Math.min(28, day));
    }

    private static String normalizeMode(String raw) {
        return switch (raw.trim().toLowerCase()) {
            case PayrollAutomationMode.REMIND -> PayrollAutomationMode.REMIND;
            case PayrollAutomationMode.AUTO_PAY -> PayrollAutomationMode.AUTO_PAY;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid automationMode");
        };
    }

    private static String normalizePaymentMethod(String raw) {
        String normalized = raw.trim().toLowerCase();
        if (FinanceConstants.EXPENSE_PAY_METHOD_CASH.equals(normalized)
                || FinanceConstants.EXPENSE_PAY_METHOD_MPESA_MANUAL.equals(normalized)
                || FinanceConstants.EXPENSE_PAY_METHOD_BANK.equals(normalized)) {
            return normalized;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paymentMethod must be cash, mpesa_manual, or bank");
    }

    private PayrollAutomationSettingsResponse toResponse(PayrollAutomationSettings settings) {
        List<String> times = SupplierAutoPayTimes.parseOrDefault(settings.getAutoPayTimesJson(), objectMapper);
        if (times.isEmpty()) {
            times = DEFAULT_TIMES;
        }
        return new PayrollAutomationSettingsResponse(
                settings.isEnabled(),
                settings.getAutomationMode(),
                settings.getPayDayOfMonth(),
                times,
                settings.isApplyStatutory(),
                settings.isPostExpense(),
                settings.getPaymentMethod(),
                settings.getBranchId(),
                settings.getAutoPayLastRunSlot()
        );
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
