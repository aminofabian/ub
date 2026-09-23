package zelisline.ub.finance.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import zelisline.ub.finance.BusinessTimeZones;
import zelisline.ub.finance.api.dto.ProfitPocketCalendarResponse;
import zelisline.ub.finance.api.dto.ProfitPocketCalendarResponse.Day;
import zelisline.ub.finance.api.dto.ProfitPocketCalendarResponse.Entry;
import zelisline.ub.finance.api.dto.ProfitPocketCalendarResponse.Insight;
import zelisline.ub.finance.api.dto.ProfitPocketCalendarResponse.Revision;
import zelisline.ub.finance.api.dto.ProfitPocketCalendarResponse.Summary;
import zelisline.ub.finance.api.dto.SkipProfitPocketDayRequest;
import zelisline.ub.finance.api.dto.UpsertProfitPocketDayRequest;
import zelisline.ub.finance.application.ProfitPocketCalendarMath.DayDraft;
import zelisline.ub.finance.application.ProfitPocketCalendarMath.DayInput;
import zelisline.ub.finance.application.ProfitPocketCalendarMath.EntryDraft;
import zelisline.ub.finance.application.ProfitPocketCalendarMath.LogDraft;
import zelisline.ub.finance.application.ProfitPocketCalendarMath.MonthSummary;
import zelisline.ub.finance.domain.ProfitPocket;
import zelisline.ub.finance.domain.ProfitPocketDay;
import zelisline.ub.finance.repository.JournalReportRepository;
import zelisline.ub.finance.repository.JournalReportRepository.DailyProfitRow;
import zelisline.ub.finance.repository.ProfitPocketDayRepository;
import zelisline.ub.finance.repository.ProfitPocketRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class ProfitPocketCalendarService {

    private static final Logger log = LoggerFactory.getLogger(ProfitPocketCalendarService.class);
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final int HISTORY_MONTHS = 6;

    private final ProfitPocketDayRepository dayRepository;
    private final ProfitPocketRepository pocketRepository;
    private final JournalReportRepository journalReportRepository;
    private final BranchRepository branchRepository;
    private final BusinessRepository businessRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ProfitPocketCalendarResponse calendar(String businessId, YearMonth month, String branchId) {
        String branch = resolveBranch(businessId, branchId);
        LocalDate today = LocalDate.now(zone(businessId));
        YearMonth startMonth = month.minusMonths(HISTORY_MONTHS - 1L);
        LocalDate from = startMonth.atDay(1);
        LocalDate to = month.atEndOfMonth();
        if (to.isBefore(from)) {
            to = from;
        }

        List<DayDraft> all = drafts(businessId, branch, from, to, today);
        List<DayDraft> monthDays = all.stream()
                .filter(day -> YearMonth.from(day.date()).equals(month))
                .toList();
        MonthSummary summary = ProfitPocketCalendarMath.summarize(month, monthDays);
        List<Summary> months = new ArrayList<>();
        MonthSummary previous = null;
        MonthSummary current = null;
        for (int i = 0; i < HISTORY_MONTHS; i++) {
            YearMonth ym = startMonth.plusMonths(i);
            MonthSummary row = ProfitPocketCalendarMath.summarize(
                    ym,
                    all.stream().filter(day -> YearMonth.from(day.date()).equals(ym)).toList());
            months.add(toSummary(row));
            if (ym.equals(month.minusMonths(1))) {
                previous = row;
            }
            if (ym.equals(month)) {
                current = row;
            }
        }
        List<Insight> insights = ProfitPocketCalendarMath.insights(current, previous).stream()
                .map(item -> new Insight(item.code(), item.current(), item.previous(), item.delta()))
                .toList();

        Map<LocalDate, List<Revision>> revisions = revisionsByDate(businessId, branch, month.atDay(1), month.atEndOfMonth());
        List<Day> days = monthDays.stream()
                .map(day -> toDay(day, revisions.getOrDefault(day.date(), List.of())))
                .toList();

        int longest = ProfitPocketCalendarMath.longestStreak(all);
        return new ProfitPocketCalendarResponse(
                month.toString(),
                branch,
                today,
                ProfitPocketCalendarMath.currentStreak(all, today),
                longest,
                days,
                toSummary(summary),
                insights,
                months
        );
    }

    @Transactional
    public ProfitPocketCalendarResponse record(String businessId, String userId, UpsertProfitPocketDayRequest req) {
        String branch = resolveBranch(businessId, req.branchId());
        LocalDate today = LocalDate.now(zone(businessId));
        if (req.date().isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You can log pocketing through today, not a future day.");
        }
        BigDecimal pocketed = money(req.pocketedAmount());
        if (pocketed.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pocketed amount must be zero or more.");
        }
        String key = branchKey(branch);
        BigDecimal live = liveProfit(businessId, branch, req.date());
        ProfitPocketDay row = dayRepository
                .findByBusinessIdAndBranchKeyAndPocketDate(businessId, key, req.date())
                .orElse(null);
        boolean refresh = Boolean.TRUE.equals(req.refreshProfit()) || row == null;
        BigDecimal snapshot = refresh || row.getProfitAmount() == null ? live : money(row.getProfitAmount());
        boolean above = exceedsProfit(snapshot, pocketed);
        if (above && !Boolean.TRUE.equals(req.allowAboveProfit())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "That amount is above the day's profit. Confirm it as a separate withdrawal to save it.");
        }
        if (row == null) {
            row = new ProfitPocketDay();
            row.setId(UUID.randomUUID().toString());
            row.setBusinessId(businessId);
            row.setBranchKey(key);
            row.setPocketDate(req.date());
            row.setCreatedBy(userId);
            row.setSource(ProfitPocketDay.SOURCE_MANUAL);
        } else if (ProfitPocketDay.SOURCE_CASH.equals(row.getSource())) {
            row.setSource(ProfitPocketDay.SOURCE_MIXED);
        }
        row.setProfitAmount(snapshot);
        row.setPocketedAmount(pocketed);
        row.setNote(trimToNull(req.note(), 500));
        row.setStatus(ProfitPocketDay.STATUS_RECORDED);
        row.setSkipReason(null);
        row.setAboveProfit(above);
        appendRevision(row, pocketed, row.getNote());
        dayRepository.save(row);
        return calendar(businessId, YearMonth.from(req.date()), branch);
    }

    @Transactional
    public ProfitPocketCalendarResponse skip(String businessId, String userId, SkipProfitPocketDayRequest req) {
        String branch = resolveBranch(businessId, req.branchId());
        LocalDate today = LocalDate.now(zone(businessId));
        if (req.date().isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A future day is not a missed day yet.");
        }
        String key = branchKey(branch);
        ProfitPocketDay row = dayRepository
                .findByBusinessIdAndBranchKeyAndPocketDate(businessId, key, req.date())
                .orElse(null);
        if (row != null && money(row.getPocketedAmount()).signum() > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "This day already has money pocketed. Update the amount instead of skipping it.");
        }
        BigDecimal live = liveProfit(businessId, branch, req.date());
        if (row == null) {
            row = new ProfitPocketDay();
            row.setId(UUID.randomUUID().toString());
            row.setBusinessId(businessId);
            row.setBranchKey(key);
            row.setPocketDate(req.date());
            row.setCreatedBy(userId);
            row.setSource(ProfitPocketDay.SOURCE_MANUAL);
            row.setPocketedAmount(ZERO);
        }
        if (row.getProfitAmount() == null || row.getProfitAmount().signum() == 0) {
            row.setProfitAmount(live);
        }
        row.setStatus(ProfitPocketDay.STATUS_SKIPPED);
        row.setSkipReason(trimToNull(req.reason(), 240));
        row.setAboveProfit(false);
        appendRevision(row, money(row.getPocketedAmount()), row.getSkipReason());
        dayRepository.save(row);
        return calendar(businessId, YearMonth.from(req.date()), branch);
    }

    @Transactional
    public ProfitPocketCalendarResponse unskip(String businessId, SkipProfitPocketDayRequest req) {
        String branch = resolveBranch(businessId, req.branchId());
        String key = branchKey(branch);
        ProfitPocketDay row = dayRepository
                .findByBusinessIdAndBranchKeyAndPocketDate(businessId, key, req.date())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That day is not marked skipped."));
        if (money(row.getPocketedAmount()).signum() == 0 && ProfitPocketDay.SOURCE_MANUAL.equals(row.getSource())) {
            dayRepository.delete(row);
        } else {
            row.setStatus(ProfitPocketDay.STATUS_RECORDED);
            row.setSkipReason(null);
            dayRepository.save(row);
        }
        return calendar(businessId, YearMonth.from(req.date()), branch);
    }

    /**
     * After a cash pocket posts, mirror it onto the day log when the owner has
     * not already typed their own amount. Failures stay in the log so the
     * journal itself still commits.
     */
    public void syncCashPocket(String businessId, String userId, ProfitPocket pocket) {
        try {
            if (pocket.getPeriodFrom() == null || pocket.getPeriodTo() == null) {
                return;
            }
            String key = branchKey(pocket.getBranchId());
            String branch = key.isEmpty() ? null : key;
            LocalDate cursor = pocket.getPeriodFrom();
            while (!cursor.isAfter(pocket.getPeriodTo())) {
                BigDecimal cash = cashTotal(businessId, key, cursor);
                ProfitPocketDay row = dayRepository
                        .findByBusinessIdAndBranchKeyAndPocketDate(businessId, key, cursor)
                        .orElse(null);
                if (row == null) {
                    row = new ProfitPocketDay();
                    row.setId(UUID.randomUUID().toString());
                    row.setBusinessId(businessId);
                    row.setBranchKey(key);
                    row.setPocketDate(cursor);
                    row.setCreatedBy(userId);
                    row.setSource(ProfitPocketDay.SOURCE_CASH);
                    row.setProfitAmount(liveProfit(businessId, branch, cursor));
                    row.setPocketedAmount(cash);
                    row.setStatus(ProfitPocketDay.STATUS_RECORDED);
                    row.setNote(trimToNull(pocket.getNote(), 500));
                    row.setAboveProfit(exceedsProfit(row.getProfitAmount(), cash));
                    appendRevision(row, cash, row.getNote());
                    dayRepository.save(row);
                } else if (ProfitPocketDay.SOURCE_CASH.equals(row.getSource())
                        && !ProfitPocketDay.STATUS_SKIPPED.equals(row.getStatus())) {
                    row.setPocketedAmount(cash);
                    row.setAboveProfit(exceedsProfit(row.getProfitAmount(), cash));
                    if (row.getNote() == null) {
                        row.setNote(trimToNull(pocket.getNote(), 500));
                    }
                    appendRevision(row, cash, row.getNote());
                    dayRepository.save(row);
                } else if (!ProfitPocketDay.SOURCE_MANUAL.equals(row.getSource())) {
                    row.setSource(ProfitPocketDay.SOURCE_MIXED);
                    dayRepository.save(row);
                } else {
                    row.setSource(ProfitPocketDay.SOURCE_MIXED);
                    dayRepository.save(row);
                }
                cursor = cursor.plusDays(1);
            }
        } catch (RuntimeException ex) {
            log.warn("Profit pocket calendar sync failed for {}", pocket.getId(), ex);
        }
    }

    private List<DayDraft> drafts(
            String businessId,
            String branch,
            LocalDate from,
            LocalDate to,
            LocalDate today
    ) {
        Map<LocalDate, BigDecimal> profit = new HashMap<>();
        Map<LocalDate, Long> sales = new HashMap<>();
        for (DailyProfitRow row : journalReportRepository.sumProfitByDay(businessId, from, to, branch)) {
            LocalDate day = toLocalDate(row.getDay());
            if (day == null) {
                continue;
            }
            profit.put(day, money(row.getProfit()));
            sales.put(day, row.getSaleCount() == null ? 0L : row.getSaleCount().longValue());
        }
        List<ProfitPocket> pockets = pocketRepository.findOverlappingPeriod(businessId, from, to);
        List<ProfitPocketDay> logs = dayRepository.findByBusinessIdAndPocketDateBetweenOrderByPocketDateAsc(
                businessId, from, to);
        String key = branchKey(branch);

        List<DayInput> inputs = new ArrayList<>();
        for (LocalDate cursor = from; !cursor.isAfter(to); cursor = cursor.plusDays(1)) {
            List<EntryDraft> entries = entriesFor(pockets, key, cursor);
            LogDraft logDraft = logFor(logs, key, cursor);
            inputs.add(new DayInput(
                    cursor,
                    profit.getOrDefault(cursor, ZERO),
                    sales.getOrDefault(cursor, 0L),
                    logDraft,
                    entries));
        }
        return ProfitPocketCalendarMath.buildDays(inputs, today);
    }

    private LogDraft logFor(List<ProfitPocketDay> logs, String key, LocalDate date) {
        List<ProfitPocketDay> sameDay = logs.stream()
                .filter(row -> date.equals(row.getPocketDate()))
                .toList();
        if (key.isEmpty()) {
            ProfitPocketDay all = sameDay.stream()
                    .filter(row -> row.getBranchKey() == null || row.getBranchKey().isBlank())
                    .findFirst()
                    .orElse(null);
            if (all != null) {
                return toLog(all);
            }
            if (sameDay.isEmpty()) {
                return null;
            }
            BigDecimal pocketed = ZERO;
            boolean skipped = true;
            String note = null;
            for (ProfitPocketDay row : sameDay) {
                pocketed = pocketed.add(money(row.getPocketedAmount()));
                if (!ProfitPocketDay.STATUS_SKIPPED.equals(row.getStatus())) {
                    skipped = false;
                }
                if (note == null) {
                    note = row.getNote();
                }
            }
            return new LogDraft(
                    money(pocketed),
                    null,
                    skipped ? ProfitPocketDay.STATUS_SKIPPED : ProfitPocketDay.STATUS_RECORDED,
                    note,
                    null,
                    ProfitPocketDay.SOURCE_MIXED,
                    false);
        }
        return sameDay.stream()
                .filter(row -> key.equals(row.getBranchKey()))
                .findFirst()
                .map(this::toLog)
                .orElse(null);
    }

    private LogDraft toLog(ProfitPocketDay row) {
        return new LogDraft(
                money(row.getPocketedAmount()),
                row.getProfitAmount() == null ? null : money(row.getProfitAmount()),
                row.getStatus(),
                row.getNote(),
                row.getSkipReason(),
                row.getSource(),
                row.isAboveProfit());
    }

    private List<EntryDraft> entriesFor(List<ProfitPocket> pockets, String key, LocalDate day) {
        List<EntryDraft> entries = new ArrayList<>();
        for (ProfitPocket pocket : pockets) {
            if (!branchMatches(key, pocket.getBranchId())) {
                continue;
            }
            BigDecimal share = ProfitPocketCalendarMath.allocate(
                    pocket.getAmount(), pocket.getPeriodFrom(), pocket.getPeriodTo(), day);
            if (share.signum() == 0) {
                continue;
            }
            entries.add(new EntryDraft(
                    pocket.getId(),
                    money(pocket.getAmount()),
                    share,
                    pocket.getPeriodFrom(),
                    pocket.getPeriodTo(),
                    summaryFromSnapshot(pocket.getDestinationSnapshotJson()),
                    pocket.getCreatedAt(),
                    pocket.getNote()));
        }
        entries.sort(Comparator.comparing(EntryDraft::createdAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return entries;
    }

    private boolean branchMatches(String key, String pocketBranchId) {
        if (key.isEmpty()) {
            return true;
        }
        return key.equals(pocketBranchId);
    }

    private BigDecimal cashTotal(String businessId, String key, LocalDate day) {
        BigDecimal total = ZERO;
        for (ProfitPocket pocket : pocketRepository.findOverlappingPeriod(businessId, day, day)) {
            if (!branchMatches(key, pocket.getBranchId())) {
                continue;
            }
            total = total.add(ProfitPocketCalendarMath.allocate(
                    pocket.getAmount(), pocket.getPeriodFrom(), pocket.getPeriodTo(), day));
        }
        return money(total);
    }

    private BigDecimal liveProfit(String businessId, String branch, LocalDate day) {
        BigDecimal total = ZERO;
        for (DailyProfitRow row : journalReportRepository.sumProfitByDay(businessId, day, day, branch)) {
            total = total.add(money(row.getProfit()));
        }
        return money(total);
    }

    private Map<LocalDate, List<Revision>> revisionsByDate(
            String businessId,
            String branch,
            LocalDate from,
            LocalDate to
    ) {
        String key = branchKey(branch);
        Map<LocalDate, List<Revision>> out = new HashMap<>();
        for (ProfitPocketDay row : dayRepository.findByBusinessIdAndPocketDateBetweenOrderByPocketDateAsc(
                businessId, from, to)) {
            if (!key.isEmpty() && !key.equals(row.getBranchKey())) {
                continue;
            }
            if (key.isEmpty() && row.getBranchKey() != null && !row.getBranchKey().isBlank()) {
                continue;
            }
            out.put(row.getPocketDate(), readRevisions(row.getRevisionsJson()));
        }
        return out;
    }

    private void appendRevision(ProfitPocketDay row, BigDecimal pocketed, String note) {
        ArrayNode array;
        try {
            JsonNode existing = row.getRevisionsJson() == null || row.getRevisionsJson().isBlank()
                    ? objectMapper.createArrayNode()
                    : objectMapper.readTree(row.getRevisionsJson());
            array = existing instanceof ArrayNode nodes ? nodes : objectMapper.createArrayNode();
        } catch (Exception ex) {
            array = objectMapper.createArrayNode();
        }
        ObjectNode item = array.addObject();
        item.put("at", Instant.now().toString());
        item.put("pocketedAmount", money(pocketed).toPlainString());
        if (note != null) {
            item.put("note", note);
        }
        while (array.size() > 20) {
            array.remove(0);
        }
        try {
            row.setRevisionsJson(objectMapper.writeValueAsString(array));
        } catch (Exception ex) {
            log.debug("Could not store pocket day revision", ex);
        }
    }

    private List<Revision> readRevisions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) {
                return List.of();
            }
            List<Revision> revisions = new ArrayList<>();
            for (JsonNode item : node) {
                String at = item.path("at").asText(null);
                String amount = item.path("pocketedAmount").asText(null);
                if (at == null || amount == null) {
                    continue;
                }
                revisions.add(new Revision(
                        Instant.parse(at),
                        new BigDecimal(amount),
                        item.path("note").isMissingNode() || item.path("note").isNull()
                                ? null
                                : item.path("note").asText()));
            }
            return revisions;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static Day toDay(DayDraft day, List<Revision> revisions) {
        return new Day(
                day.date(),
                day.saleCount(),
                day.liveProfit(),
                day.profitAmount(),
                day.pocketedAmount(),
                day.remainingProfit(),
                day.pocketingPercentage(),
                day.status(),
                day.note(),
                day.skipReason(),
                day.source(),
                day.aboveProfit(),
                day.profitMoved(),
                day.entries().stream().map(entry -> new Entry(
                        entry.id(),
                        entry.amount(),
                        entry.attributedAmount(),
                        entry.periodFrom(),
                        entry.periodTo(),
                        entry.destinationSummary(),
                        entry.createdAt(),
                        entry.note())).toList(),
                revisions);
    }

    private static Summary toSummary(MonthSummary row) {
        return new Summary(
                row.month(),
                row.label(),
                row.totalProfit(),
                row.totalPocketed(),
                row.totalRetained(),
                row.averageDailyPercentage(),
                row.overallPercentage(),
                row.pocketingDays(),
                row.partialDays(),
                row.fullDays(),
                row.missedDays(),
                row.unreviewedDays(),
                row.skippedDays(),
                row.noProfitDays(),
                row.longestStreak(),
                row.highestPocketDate(),
                row.highestPocketAmount(),
                row.averagePocketedPerDay(),
                row.profitNotPocketed());
    }

    private String resolveBranch(String businessId, String branchId) {
        if (branchId == null || branchId.isBlank()) {
            return null;
        }
        String trimmed = branchId.trim();
        branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(trimmed, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
        return trimmed;
    }

    private ZoneId zone(String businessId) {
        Business business = businessRepository.findById(businessId).orElse(null);
        return BusinessTimeZones.of(business);
    }

    private String summaryFromSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode summary = node.get("summary");
            return summary == null || summary.isNull() ? null : summary.asText();
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean exceedsProfit(BigDecimal profit, BigDecimal pocketed) {
        if (pocketed.signum() <= 0) {
            return false;
        }
        if (profit == null || profit.signum() <= 0) {
            return true;
        }
        return pocketed.subtract(money(profit)).compareTo(new BigDecimal("0.009")) > 0;
    }

    static String branchKey(String branchId) {
        return branchId == null || branchId.isBlank() ? "" : branchId.trim();
    }

    private static BigDecimal money(BigDecimal value) {
        if (value == null) {
            return ZERO;
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String trimToNull(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static LocalDate toLocalDate(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof LocalDate date) {
            return date;
        }
        if (raw instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        String text = raw.toString();
        if (text.length() >= 10) {
            return LocalDate.parse(text.substring(0, 10));
        }
        return null;
    }
}
