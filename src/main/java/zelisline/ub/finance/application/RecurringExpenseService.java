package zelisline.ub.finance.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.finance.FinanceConstants;
import zelisline.ub.finance.api.dto.PostExpenseRequest;
import zelisline.ub.finance.domain.Expense;
import zelisline.ub.finance.domain.ExpenseSchedule;
import zelisline.ub.finance.domain.ExpenseScheduleOccurrence;
import zelisline.ub.finance.repository.ExpenseScheduleOccurrenceRepository;
import zelisline.ub.finance.repository.ExpenseScheduleRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class RecurringExpenseService {

    private final BusinessRepository businessRepository;
    private final ExpenseScheduleRepository expenseScheduleRepository;
    private final ExpenseScheduleOccurrenceRepository occurrenceRepository;
    private final ExpenseService expenseService;

    @Transactional
    public int processAllBusinessesDueToday() {
        int total = 0;
        List<Business> businesses = businessRepository.findByDeletedAtIsNull(org.springframework.data.domain.Pageable.unpaged())
                .getContent();
        for (Business business : businesses) {
            ZoneId zone = ZoneId.of(business.getTimezone());
            LocalDate businessDate = LocalDate.now(zone);
            total += processBusinessForDate(business.getId(), businessDate);
        }
        return total;
    }

    @Transactional
    public int processBusinessForDate(String businessId, LocalDate businessDate) {
        int posted = 0;
        List<ExpenseSchedule> schedules = expenseScheduleRepository.findByBusinessIdAndActiveTrue(businessId);
        for (ExpenseSchedule schedule : schedules) {
            posted += processScheduleForDate(schedule, businessDate);
        }
        return posted;
    }

    @Transactional
    public int processScheduleForDate(ExpenseSchedule schedule, LocalDate businessDate) {
        if (!schedule.isActive()) {
            return 0;
        }
        if (schedule.getStartDate().isAfter(businessDate)) {
            return 0;
        }
        if (schedule.getEndDate() != null && schedule.getEndDate().isBefore(businessDate)) {
            return 0;
        }
        LocalDate cursor = schedule.getLastGeneratedOn() != null
                ? nextDueDate(schedule, schedule.getLastGeneratedOn())
                : schedule.getStartDate();
        int posted = 0;
        while (cursor != null && !cursor.isAfter(businessDate)) {
            if (schedule.getEndDate() != null && cursor.isAfter(schedule.getEndDate())) {
                break;
            }
            if (isDueOn(schedule, cursor) && ensureOccurrenceAndPost(schedule, cursor, schedule.getCreatedBy())) {
                posted++;
            }
            schedule.setLastGeneratedOn(cursor);
            cursor = nextDueDate(schedule, cursor);
        }
        expenseScheduleRepository.save(schedule);
        return posted;
    }

    @Transactional
    public ExpenseScheduleOccurrence postOccurrenceForDate(
            ExpenseSchedule schedule,
            LocalDate date,
            String userId
    ) {
        ExpenseScheduleOccurrence occ = occurrenceRepository
                .findByScheduleIdAndOccurrenceDate(schedule.getId(), date)
                .orElse(null);
        if (occ == null) {
            ensureOccurrenceAndPost(schedule, date, userId);
            occ = occurrenceRepository.findByScheduleIdAndOccurrenceDate(schedule.getId(), date)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Could not post occurrence"
                    ));
        } else {
            occ = postExistingOccurrence(schedule, occ, userId);
        }
        advanceLastGeneratedOn(schedule, date);
        expenseScheduleRepository.save(schedule);
        return occ;
    }

    @Transactional
    public ExpenseScheduleOccurrence postExistingOccurrence(
            ExpenseSchedule schedule,
            ExpenseScheduleOccurrence occ,
            String userId
    ) {
        if (FinanceConstants.OCCURRENCE_STATUS_POSTED.equals(occ.getStatus()) && occ.getExpenseId() != null) {
            return occ;
        }
        if (FinanceConstants.OCCURRENCE_STATUS_SKIPPED.equals(occ.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Occurrence was skipped");
        }
        try {
            Expense expense = expenseService.createRecurringExpense(
                    schedule.getBusinessId(),
                    new PostExpenseRequest(
                            occ.getOccurrenceDate(),
                            schedule.getName(),
                            schedule.getCategoryType(),
                            schedule.getAmount(),
                            schedule.getPaymentMethod(),
                            schedule.isIncludeInCashDrawer(),
                            schedule.getBranchId(),
                            schedule.getReceiptS3Key(),
                            schedule.getExpenseLedgerAccountId(),
                            Instant.now()
                    ),
                    userId
            );
            occ.setExpenseId(expense.getId());
            occ.setPostedAt(Instant.now());
            occ.setStatus(FinanceConstants.OCCURRENCE_STATUS_POSTED);
            occ.setFailureReason(null);
            ExpenseScheduleOccurrence saved = occurrenceRepository.save(occ);
            advanceLastGeneratedOn(schedule, occ.getOccurrenceDate());
            expenseScheduleRepository.save(schedule);
            return saved;
        } catch (RuntimeException ex) {
            occ.setStatus(FinanceConstants.OCCURRENCE_STATUS_FAILED);
            occ.setFailureReason(limit(ex.getMessage(), 1000));
            return occurrenceRepository.save(occ);
        }
    }

    private boolean ensureOccurrenceAndPost(ExpenseSchedule schedule, LocalDate date, String userId) {
        ExpenseScheduleOccurrence existing = occurrenceRepository
                .findByScheduleIdAndOccurrenceDate(schedule.getId(), date)
                .orElse(null);
        if (existing != null) {
            return FinanceConstants.OCCURRENCE_STATUS_POSTED.equals(existing.getStatus())
                    && existing.getExpenseId() != null;
        }

        if (FinanceConstants.AUTOMATION_MODE_REMIND.equals(schedule.getAutomationMode())) {
            ExpenseScheduleOccurrence occ = new ExpenseScheduleOccurrence();
            occ.setScheduleId(schedule.getId());
            occ.setBusinessId(schedule.getBusinessId());
            occ.setOccurrenceDate(date);
            occ.setStatus(FinanceConstants.OCCURRENCE_STATUS_DUE);
            try {
                occurrenceRepository.save(occ);
            } catch (DataIntegrityViolationException e) {
                return false;
            }
            return false;
        }

        ExpenseScheduleOccurrence occ = new ExpenseScheduleOccurrence();
        occ.setScheduleId(schedule.getId());
        occ.setBusinessId(schedule.getBusinessId());
        occ.setOccurrenceDate(date);
        occ.setStatus(FinanceConstants.OCCURRENCE_STATUS_POSTED);
        try {
            occurrenceRepository.save(occ);
        } catch (DataIntegrityViolationException e) {
            return false;
        }

        try {
            Expense expense = expenseService.createRecurringExpense(
                    schedule.getBusinessId(),
                    new PostExpenseRequest(
                            date,
                            schedule.getName(),
                            schedule.getCategoryType(),
                            schedule.getAmount(),
                            schedule.getPaymentMethod(),
                            schedule.isIncludeInCashDrawer(),
                            schedule.getBranchId(),
                            schedule.getReceiptS3Key(),
                            schedule.getExpenseLedgerAccountId(),
                            Instant.now()
                    ),
                    userId
            );
            occ.setExpenseId(expense.getId());
            occ.setPostedAt(Instant.now());
            occ.setStatus(FinanceConstants.OCCURRENCE_STATUS_POSTED);
            occurrenceRepository.save(occ);
            return true;
        } catch (RuntimeException ex) {
            occ.setStatus(FinanceConstants.OCCURRENCE_STATUS_FAILED);
            occ.setFailureReason(limit(ex.getMessage(), 1000));
            occurrenceRepository.save(occ);
            return false;
        }
    }

    private void advanceLastGeneratedOn(ExpenseSchedule schedule, LocalDate date) {
        if (schedule.getLastGeneratedOn() == null || date.isAfter(schedule.getLastGeneratedOn())) {
            schedule.setLastGeneratedOn(date);
        }
    }

    static boolean isDueOn(ExpenseSchedule schedule, LocalDate date) {
        if (date.isBefore(schedule.getStartDate())) {
            return false;
        }
        String freq = schedule.getFrequency();
        if (FinanceConstants.EXPENSE_FREQUENCY_DAILY.equals(freq)) {
            return true;
        }
        if (FinanceConstants.EXPENSE_FREQUENCY_WEEKLY.equals(freq)) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(schedule.getStartDate(), date);
            return days % 7 == 0;
        }
        if (FinanceConstants.EXPENSE_FREQUENCY_MONTHLY.equals(freq)) {
            int anchor = schedule.getStartDate().getDayOfMonth();
            int day = Math.min(anchor, YearMonth.from(date).lengthOfMonth());
            return date.getDayOfMonth() == day;
        }
        return false;
    }

    static LocalDate nextDueDate(ExpenseSchedule schedule, LocalDate current) {
        String freq = schedule.getFrequency();
        if (FinanceConstants.EXPENSE_FREQUENCY_DAILY.equals(freq)) {
            return current.plusDays(1);
        }
        if (FinanceConstants.EXPENSE_FREQUENCY_WEEKLY.equals(freq)) {
            return current.plusWeeks(1);
        }
        if (FinanceConstants.EXPENSE_FREQUENCY_MONTHLY.equals(freq)) {
            LocalDate nextMonth = current.plusMonths(1);
            int anchor = schedule.getStartDate().getDayOfMonth();
            int day = Math.min(anchor, YearMonth.from(nextMonth).lengthOfMonth());
            return nextMonth.withDayOfMonth(day);
        }
        return null;
    }

    private static String limit(String input, int max) {
        if (input == null) {
            return null;
        }
        if (input.length() <= max) {
            return input;
        }
        return input.substring(0, max);
    }
}
