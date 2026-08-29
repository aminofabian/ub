package zelisline.ub.finance.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.finance.FinanceConstants;
import zelisline.ub.finance.api.dto.ExpenseCalendarMonthResponse;
import zelisline.ub.finance.api.dto.ExpenseCalendarResponse;
import zelisline.ub.finance.api.dto.ExpenseScheduleOccurrenceResponse;
import zelisline.ub.finance.domain.ExpenseSchedule;
import zelisline.ub.finance.domain.ExpenseScheduleOccurrence;
import zelisline.ub.finance.repository.ExpenseScheduleOccurrenceRepository;
import zelisline.ub.finance.repository.ExpenseScheduleRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class ExpenseScheduleOccurrenceService {

    private final BusinessRepository businessRepository;
    private final ExpenseScheduleRepository expenseScheduleRepository;
    private final ExpenseScheduleOccurrenceRepository occurrenceRepository;
    private final RecurringExpenseService recurringExpenseService;

    @Transactional(readOnly = true)
    public List<ExpenseScheduleOccurrenceResponse> listForMonth(
            String businessId,
            int year,
            int month,
            String branchId
    ) {
        LocalDate today = businessToday(businessId);
        YearMonth ym = YearMonth.of(year, month);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();

        List<ExpenseSchedule> schedules = filterSchedules(
                expenseScheduleRepository.findByBusinessIdAndActiveTrue(businessId),
                branchId
        );
        Map<String, ExpenseSchedule> scheduleById = new HashMap<>();
        for (ExpenseSchedule schedule : schedules) {
            scheduleById.put(schedule.getId(), schedule);
        }

        Map<String, ExpenseScheduleOccurrence> existingByKey = new HashMap<>();
        for (ExpenseScheduleOccurrence occ : occurrenceRepository.findByBusinessIdAndOccurrenceDateBetween(
                businessId,
                start,
                end
        )) {
            existingByKey.put(key(occ.getScheduleId(), occ.getOccurrenceDate()), occ);
            expenseScheduleRepository.findByIdAndBusinessId(occ.getScheduleId(), businessId)
                    .ifPresent(s -> scheduleById.putIfAbsent(s.getId(), s));
        }

        Set<String> emitted = new HashSet<>();
        List<ExpenseScheduleOccurrenceResponse> rows = new ArrayList<>();

        for (ExpenseSchedule schedule : schedules) {
            for (LocalDate date : dueDatesInRange(schedule, start, end)) {
                String occKey = key(schedule.getId(), date);
                emitted.add(occKey);
                ExpenseScheduleOccurrence occ = existingByKey.get(occKey);
                if (occ != null) {
                    rows.add(toResponse(schedule, occ));
                } else {
                    rows.add(projectedResponse(schedule, date, today));
                }
            }
        }

        for (Map.Entry<String, ExpenseScheduleOccurrence> entry : existingByKey.entrySet()) {
            if (emitted.contains(entry.getKey())) {
                continue;
            }
            ExpenseScheduleOccurrence occ = entry.getValue();
            ExpenseSchedule schedule = scheduleById.get(occ.getScheduleId());
            if (schedule == null) {
                schedule = expenseScheduleRepository.findByIdAndBusinessId(occ.getScheduleId(), businessId)
                        .orElse(null);
            }
            if (schedule == null || !matchesBranch(schedule, branchId)) {
                continue;
            }
            rows.add(toResponse(schedule, occ));
        }

        rows.sort(Comparator
                .comparing(ExpenseScheduleOccurrenceResponse::occurrenceDate)
                .thenComparing(ExpenseScheduleOccurrenceResponse::scheduleName));
        return rows;
    }

    @Transactional(readOnly = true)
    public ExpenseCalendarResponse calendarYear(String businessId, int year, String branchId) {
        LocalDate today = businessToday(businessId);
        List<ExpenseSchedule> schedules = filterSchedules(
                expenseScheduleRepository.findByBusinessIdAndActiveTrue(businessId),
                branchId
        );

        List<ExpenseCalendarMonthResponse> months = new ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            YearMonth ym = YearMonth.of(year, month);
            LocalDate start = ym.atDay(1);
            LocalDate end = ym.atEndOfMonth();

            Map<String, ExpenseScheduleOccurrence> existingByKey = new HashMap<>();
            for (ExpenseScheduleOccurrence occ : occurrenceRepository.findByBusinessIdAndOccurrenceDateBetween(
                    businessId,
                    start,
                    end
            )) {
                existingByKey.put(key(occ.getScheduleId(), occ.getOccurrenceDate()), occ);
            }

            int dueCount = 0;
            int postedCount = 0;
            int failedCount = 0;
            int skippedCount = 0;
            BigDecimal commitment = BigDecimal.ZERO;
            BigDecimal postedTotal = BigDecimal.ZERO;
            Set<String> countedDue = new HashSet<>();

            for (ExpenseSchedule schedule : schedules) {
                for (LocalDate date : dueDatesInRange(schedule, start, end)) {
                    String occKey = key(schedule.getId(), date);
                    if (!countedDue.add(occKey)) {
                        continue;
                    }
                    dueCount++;
                    commitment = commitment.add(schedule.getAmount());
                    ExpenseScheduleOccurrence occ = existingByKey.get(occKey);
                    if (occ == null) {
                        continue;
                    }
                    if (FinanceConstants.OCCURRENCE_STATUS_POSTED.equals(occ.getStatus())) {
                        postedCount++;
                        postedTotal = postedTotal.add(schedule.getAmount());
                    } else if (FinanceConstants.OCCURRENCE_STATUS_FAILED.equals(occ.getStatus())) {
                        failedCount++;
                    } else if (FinanceConstants.OCCURRENCE_STATUS_SKIPPED.equals(occ.getStatus())) {
                        skippedCount++;
                    }
                }
            }

            months.add(ExpenseCalendarSummarizer.summarize(
                    year,
                    month,
                    dueCount,
                    postedCount,
                    failedCount,
                    skippedCount,
                    commitment,
                    postedTotal,
                    today
            ));
        }
        return new ExpenseCalendarResponse(year, months);
    }

    @Transactional
    public ExpenseScheduleOccurrenceResponse postById(String businessId, String occurrenceId, String userId) {
        ExpenseScheduleOccurrence occ = occurrenceRepository.findByIdAndBusinessId(occurrenceId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Occurrence not found"));
        ExpenseSchedule schedule = requireSchedule(businessId, occ.getScheduleId());
        ExpenseScheduleOccurrence saved = recurringExpenseService.postExistingOccurrence(schedule, occ, userId);
        return toResponse(schedule, saved);
    }

    @Transactional
    public ExpenseScheduleOccurrenceResponse postByScheduleDate(
            String businessId,
            String scheduleId,
            LocalDate occurrenceDate,
            String userId
    ) {
        ExpenseSchedule schedule = requireSchedule(businessId, scheduleId);
        if (!RecurringExpenseService.isDueOn(schedule, occurrenceDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date is not a due date for this schedule");
        }
        ExpenseScheduleOccurrence saved = recurringExpenseService.postOccurrenceForDate(schedule, occurrenceDate, userId);
        return toResponse(schedule, saved);
    }

    @Transactional
    public ExpenseScheduleOccurrenceResponse skipById(String businessId, String occurrenceId) {
        ExpenseScheduleOccurrence occ = occurrenceRepository.findByIdAndBusinessId(occurrenceId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Occurrence not found"));
        if (FinanceConstants.OCCURRENCE_STATUS_POSTED.equals(occ.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Occurrence already posted");
        }
        ExpenseSchedule schedule = requireSchedule(businessId, occ.getScheduleId());
        occ.setStatus(FinanceConstants.OCCURRENCE_STATUS_SKIPPED);
        occ.setFailureReason(null);
        occ.setExpenseId(null);
        occ.setPostedAt(null);
        ExpenseScheduleOccurrence saved = occurrenceRepository.save(occ);
        return toResponse(schedule, saved);
    }

    @Transactional
    public ExpenseScheduleOccurrenceResponse skipByScheduleDate(
            String businessId,
            String scheduleId,
            LocalDate occurrenceDate
    ) {
        ExpenseSchedule schedule = requireSchedule(businessId, scheduleId);
        if (!RecurringExpenseService.isDueOn(schedule, occurrenceDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date is not a due date for this schedule");
        }
        ExpenseScheduleOccurrence occ = occurrenceRepository
                .findByScheduleIdAndOccurrenceDate(scheduleId, occurrenceDate)
                .orElseGet(() -> {
                    ExpenseScheduleOccurrence created = new ExpenseScheduleOccurrence();
                    created.setScheduleId(scheduleId);
                    created.setBusinessId(businessId);
                    created.setOccurrenceDate(occurrenceDate);
                    created.setStatus(FinanceConstants.OCCURRENCE_STATUS_SKIPPED);
                    return created;
                });
        if (FinanceConstants.OCCURRENCE_STATUS_POSTED.equals(occ.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Occurrence already posted");
        }
        occ.setStatus(FinanceConstants.OCCURRENCE_STATUS_SKIPPED);
        occ.setFailureReason(null);
        occ.setExpenseId(null);
        occ.setPostedAt(null);
        ExpenseScheduleOccurrence saved = occurrenceRepository.save(occ);
        return toResponse(schedule, saved);
    }

    private ExpenseSchedule requireSchedule(String businessId, String scheduleId) {
        return expenseScheduleRepository.findByIdAndBusinessId(scheduleId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Expense schedule not found"));
    }

    private LocalDate businessToday(String businessId) {
        Business business = businessRepository.findById(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));
        ZoneId zone = ZoneId.of(business.getTimezone());
        return LocalDate.now(zone);
    }

    private static List<ExpenseSchedule> filterSchedules(List<ExpenseSchedule> schedules, String branchId) {
        if (branchId == null || branchId.isBlank()) {
            return schedules;
        }
        return schedules.stream()
                .filter(s -> branchId.equals(s.getBranchId()) || s.getBranchId() == null)
                .toList();
    }

    private static boolean matchesBranch(ExpenseSchedule schedule, String branchId) {
        if (branchId == null || branchId.isBlank()) {
            return true;
        }
        return branchId.equals(schedule.getBranchId()) || schedule.getBranchId() == null;
    }

    static List<LocalDate> dueDatesInRange(ExpenseSchedule schedule, LocalDate start, LocalDate end) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            if (RecurringExpenseService.isDueOn(schedule, cursor)
                    && !cursor.isBefore(schedule.getStartDate())
                    && (schedule.getEndDate() == null || !cursor.isAfter(schedule.getEndDate()))) {
                dates.add(cursor);
            }
            cursor = cursor.plusDays(1);
        }
        return dates;
    }

    private static String key(String scheduleId, LocalDate date) {
        return scheduleId + ":" + date;
    }

    private static ExpenseScheduleOccurrenceResponse projectedResponse(
            ExpenseSchedule schedule,
            LocalDate date,
            LocalDate today
    ) {
        String status = date.isAfter(today)
                ? FinanceConstants.OCCURRENCE_STATUS_UPCOMING
                : FinanceConstants.OCCURRENCE_STATUS_DUE;
        return new ExpenseScheduleOccurrenceResponse(
                null,
                schedule.getId(),
                schedule.getName(),
                schedule.getBranchId(),
                date,
                status,
                schedule.getAmount(),
                schedule.getPaymentMethod(),
                null,
                null,
                null
        );
    }

    static ExpenseScheduleOccurrenceResponse toResponse(ExpenseSchedule schedule, ExpenseScheduleOccurrence occ) {
        return new ExpenseScheduleOccurrenceResponse(
                occ.getId(),
                schedule.getId(),
                schedule.getName(),
                schedule.getBranchId(),
                occ.getOccurrenceDate(),
                occ.getStatus(),
                schedule.getAmount(),
                schedule.getPaymentMethod(),
                occ.getExpenseId(),
                occ.getPostedAt(),
                occ.getFailureReason()
        );
    }
}
