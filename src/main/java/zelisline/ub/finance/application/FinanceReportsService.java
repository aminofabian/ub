package zelisline.ub.finance.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.finance.BusinessTimeZones;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.VatBucket;
import zelisline.ub.finance.api.dto.BalanceSheetResponse;
import zelisline.ub.finance.api.dto.DailyProfitPoint;
import zelisline.ub.finance.api.dto.FinancePulseResponse;
import zelisline.ub.finance.api.dto.ProfitAndLossResponse;
import zelisline.ub.finance.api.dto.VatBucketResponse;
import zelisline.ub.finance.api.dto.VatReportResponse;
import zelisline.ub.finance.repository.JournalReportRepository;
import zelisline.ub.finance.repository.JournalReportRepository.AccountBalance;
import zelisline.ub.finance.repository.JournalReportRepository.SaleAggregate;
import zelisline.ub.finance.repository.JournalReportRepository.RefundAggregate;
import zelisline.ub.finance.repository.JournalReportRepository.TaxCodeVatRow;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Owner pulse, simple P&amp;L, and simple balance sheet — the Phase 6 close-out gate
 * Phase 7 Slice 0 promises before MV optimisation begins. All three are journal-backed
 * (with sales/expenses cross-checks for pulse "today") so Phase 7 Slices 2+ can layer MVs
 * over the same numbers without behavioural drift.
 *
 * <p><b>Day window rule (ENP-3):</b> business timezone is canonical (same as recurring
 * expense scheduler). Pulse sales use {@code [local midnight, next local midnight)} as
 * Instants; expense day lists and default "today" use {@code LocalDate.now(zone)}.
 */
@Service
@RequiredArgsConstructor
public class FinanceReportsService {

    private static final BigDecimal MONEY_TOL = new BigDecimal("0.01");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final JournalReportRepository journalReportRepository;
    private final BranchRepository branchRepository;
    private final BusinessRepository businessRepository;

    @Transactional(readOnly = true)
    public FinancePulseResponse pulse(String businessId, LocalDate date, String branchId) {
        return pulse(businessId, date, branchId, null);
    }

    @Transactional(readOnly = true)
    public FinancePulseResponse pulse(String businessId, LocalDate date, String branchId, String itemTypeId) {
        ZoneId zone = resolveZone(businessId);
        LocalDate day = date != null ? date : LocalDate.now(zone);
        String resolvedBranch = resolveBranch(businessId, branchId);
        String resolvedType = blankToNull(itemTypeId);

        Instant windowStart = day.atStartOfDay(zone).toInstant();
        Instant windowEnd = day.plusDays(1).atStartOfDay(zone).toInstant();

        SaleAggregate sales = journalReportRepository.sumSalesForWindow(
                businessId,
                windowStart,
                windowEnd,
                resolvedBranch,
                resolvedType
        );
        long saleCount = sales != null ? sales.getSaleCount() : 0L;
        BigDecimal grossRevenue = money(sales != null ? sales.getRevenue() : null);
        BigDecimal grossCogs = money(sales != null ? sales.getCogs() : null);

        // Net refunds processed in the same business-day window so "today" uses the same refund
        // treatment as the ledger P&L (and the Hub's week card). Skipped for a department scope,
        // which has no journal refund dimension — the department P&L ignores refunds too.
        RefundAggregate refunds = resolvedType != null
                ? null
                : journalReportRepository.sumRefundsForWindow(
                        businessId, windowStart, windowEnd, resolvedBranch);
        BigDecimal refundRevenue = money(refunds != null ? refunds.getRefundRevenue() : null);
        BigDecimal refundCogs = money(refunds != null ? refunds.getRefundCogs() : null);
        BigDecimal revenue = grossRevenue.subtract(refundRevenue).setScale(2, RoundingMode.HALF_UP);
        BigDecimal cogs = grossCogs.subtract(refundCogs).setScale(2, RoundingMode.HALF_UP);
        BigDecimal profit = revenue.subtract(cogs).setScale(2, RoundingMode.HALF_UP);

        // Expenses are not department-attributed; omit them when a department filter is active
        // so net operating stays meaningful for the scoped revenue.
        BigDecimal expensesTotal = resolvedType != null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : money(journalReportRepository.sumExpensesForDate(businessId, day, resolvedBranch));
        BigDecimal grossMarginPct = revenue.signum() == 0
                ? BigDecimal.ZERO
                : profit.multiply(HUNDRED).divide(revenue, 2, RoundingMode.HALF_UP);
        BigDecimal netOperating = profit.subtract(expensesTotal).setScale(2, RoundingMode.HALF_UP);
        long openShifts = journalReportRepository.countOpenShifts(businessId, resolvedBranch);

        return new FinancePulseResponse(
                day,
                resolvedBranch,
                saleCount,
                revenue,
                cogs,
                profit,
                grossMarginPct,
                expensesTotal,
                netOperating,
                openShifts
        );
    }

    @Transactional(readOnly = true)
    public ProfitAndLossResponse profitAndLoss(String businessId, LocalDate from, LocalDate to, String branchId) {
        return profitAndLoss(businessId, from, to, branchId, null);
    }

    @Transactional(readOnly = true)
    public ProfitAndLossResponse profitAndLoss(
            String businessId,
            LocalDate from,
            LocalDate to,
            String branchId,
            String itemTypeId
    ) {
        if (from == null || to == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from and to are required");
        }
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "to must be on or after from");
        }
        String resolvedBranch = resolveBranch(businessId, branchId);
        String resolvedType = blankToNull(itemTypeId);

        // Department-scoped P&L has no journal dimension — use sale-line revenue/COGS instead.
        if (resolvedType != null) {
            SaleAggregate sales = journalReportRepository.sumSalesForPeriod(
                    businessId, from, to, resolvedBranch, resolvedType);
            BigDecimal revenue = money(sales != null ? sales.getRevenue() : null);
            BigDecimal cogs = money(sales != null ? sales.getCogs() : null);
            BigDecimal grossProfit = revenue.subtract(cogs).setScale(2, RoundingMode.HALF_UP);
            BigDecimal operatingExpenses = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            return new ProfitAndLossResponse(
                    from,
                    to,
                    resolvedBranch,
                    revenue,
                    cogs,
                    grossProfit,
                    operatingExpenses,
                    grossProfit,
                    List.of(),
                    List.of(),
                    List.of()
            );
        }

        List<AccountBalance> rows = journalReportRepository.sumByAccountForPeriod(
                businessId, from, to, resolvedBranch);
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal cogs = BigDecimal.ZERO;
        BigDecimal operatingExpenses = BigDecimal.ZERO;
        List<ProfitAndLossResponse.LineItem> revenueLines = new ArrayList<>();
        List<ProfitAndLossResponse.LineItem> cogsLines = new ArrayList<>();
        List<ProfitAndLossResponse.LineItem> expenseLines = new ArrayList<>();

        for (AccountBalance row : rows) {
            String type = normalizedType(row.getAccountType());
            BigDecimal credit = money(row.getCreditTotal());
            BigDecimal debit = money(row.getDebitTotal());
            switch (type) {
                case "revenue" -> {
                    BigDecimal amount = credit.subtract(debit).setScale(2, RoundingMode.HALF_UP);
                    if (amount.signum() != 0) {
                        revenue = revenue.add(amount);
                        revenueLines.add(line(row, amount));
                    }
                }
                case "expense" -> {
                    BigDecimal amount = debit.subtract(credit).setScale(2, RoundingMode.HALF_UP);
                    if (amount.signum() != 0) {
                        if (LedgerAccountCodes.COST_OF_GOODS_SOLD.equals(row.getCode())) {
                            cogs = cogs.add(amount);
                            cogsLines.add(line(row, amount));
                        } else {
                            operatingExpenses = operatingExpenses.add(amount);
                            expenseLines.add(line(row, amount));
                        }
                    }
                }
                default -> { /* asset / liability / equity ignored for P&L */ }
            }
        }

        BigDecimal grossProfit = revenue.subtract(cogs).setScale(2, RoundingMode.HALF_UP);
        BigDecimal netOperating = grossProfit.subtract(operatingExpenses).setScale(2, RoundingMode.HALF_UP);
        sortByCode(revenueLines);
        sortByCode(cogsLines);
        sortByCode(expenseLines);

        return new ProfitAndLossResponse(
                from,
                to,
                resolvedBranch,
                revenue.setScale(2, RoundingMode.HALF_UP),
                cogs.setScale(2, RoundingMode.HALF_UP),
                grossProfit,
                operatingExpenses.setScale(2, RoundingMode.HALF_UP),
                netOperating,
                List.copyOf(revenueLines),
                List.copyOf(cogsLines),
                List.copyOf(expenseLines)
        );
    }

    /**
     * Daily net-profit strip: one {@link DailyProfitPoint} per calendar day in
     * {@code from..to}, zero-filled when a day has no journal activity. Same
     * classification as {@link #profitAndLoss} (COGS is account 5000 only).
     */
    @Transactional(readOnly = true)
    public List<DailyProfitPoint> dailyProfitAndLoss(
            String businessId,
            LocalDate from,
            LocalDate to,
            String branchId
    ) {
        if (from == null || to == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from and to are required");
        }
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "to must be on or after from");
        }
        String resolvedBranch = resolveBranch(businessId, branchId);

        List<JournalReportRepository.DailyAccountBalance> rows =
                journalReportRepository.sumByAccountForPeriodByDay(businessId, from, to, resolvedBranch);

        Map<LocalDate, BigDecimal[]> byDay = new HashMap<>();
        for (JournalReportRepository.DailyAccountBalance row : rows) {
            LocalDate day = toLocalDate(row.getJournalDay());
            if (day == null) {
                continue;
            }
            BigDecimal[] acc = byDay.computeIfAbsent(day, k -> new BigDecimal[] {
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO });
            String type = normalizedType(row.getAccountType());
            BigDecimal credit = money(row.getCreditTotal());
            BigDecimal debit = money(row.getDebitTotal());
            switch (type) {
                case "revenue" -> acc[0] = acc[0].add(credit.subtract(debit));
                case "expense" -> {
                    BigDecimal amount = debit.subtract(credit);
                    if (LedgerAccountCodes.COST_OF_GOODS_SOLD.equals(row.getCode())) {
                        acc[1] = acc[1].add(amount);
                    } else {
                        acc[2] = acc[2].add(amount);
                    }
                }
                default -> { /* asset / liability / equity ignored */ }
            }
        }

        List<DailyProfitPoint> out = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            BigDecimal[] acc = byDay.getOrDefault(day, new BigDecimal[] {
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO });
            BigDecimal revenue = acc[0].setScale(2, RoundingMode.HALF_UP);
            BigDecimal cogs = acc[1].setScale(2, RoundingMode.HALF_UP);
            BigDecimal opex = acc[2].setScale(2, RoundingMode.HALF_UP);
            BigDecimal gross = revenue.subtract(cogs).setScale(2, RoundingMode.HALF_UP);
            BigDecimal net = gross.subtract(opex).setScale(2, RoundingMode.HALF_UP);
            out.add(new DailyProfitPoint(
                    day, revenue, cogs, gross, opex, net, byDay.containsKey(day)));
        }
        return out;
    }

    private static LocalDate toLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        return LocalDate.parse(value.toString());
    }

    @Transactional(readOnly = true)
    public BalanceSheetResponse balanceSheet(String businessId, LocalDate asOf, String branchId) {
        ZoneId zone = resolveZone(businessId);
        LocalDate target = asOf != null ? asOf : LocalDate.now(zone);
        String resolvedBranch = resolveBranch(businessId, branchId);

        List<AccountBalance> rows = journalReportRepository.sumByAccountAsOf(businessId, target, resolvedBranch);
        List<BalanceSheetResponse.LineItem> assets = new ArrayList<>();
        List<BalanceSheetResponse.LineItem> liabilities = new ArrayList<>();
        List<BalanceSheetResponse.LineItem> equity = new ArrayList<>();
        BigDecimal totalAssets = BigDecimal.ZERO;
        BigDecimal totalLiabilities = BigDecimal.ZERO;
        BigDecimal totalEquity = BigDecimal.ZERO;
        BigDecimal periodRevenue = BigDecimal.ZERO;
        BigDecimal periodExpense = BigDecimal.ZERO;

        for (AccountBalance row : rows) {
            String type = normalizedType(row.getAccountType());
            BigDecimal credit = money(row.getCreditTotal());
            BigDecimal debit = money(row.getDebitTotal());
            switch (type) {
                case "asset" -> {
                    BigDecimal amount = debit.subtract(credit).setScale(2, RoundingMode.HALF_UP);
                    if (amount.signum() != 0) {
                        totalAssets = totalAssets.add(amount);
                        assets.add(bsLine(row, amount));
                    }
                }
                case "liability" -> {
                    BigDecimal amount = credit.subtract(debit).setScale(2, RoundingMode.HALF_UP);
                    if (amount.signum() != 0) {
                        totalLiabilities = totalLiabilities.add(amount);
                        liabilities.add(bsLine(row, amount));
                    }
                }
                case "equity" -> {
                    BigDecimal amount = credit.subtract(debit).setScale(2, RoundingMode.HALF_UP);
                    if (amount.signum() != 0) {
                        totalEquity = totalEquity.add(amount);
                        equity.add(bsLine(row, amount));
                    }
                }
                case "revenue" -> periodRevenue = periodRevenue.add(credit.subtract(debit));
                case "expense" -> periodExpense = periodExpense.add(debit.subtract(credit));
                default -> { /* unknown type ignored */ }
            }
        }

        BigDecimal currentEarnings = periodRevenue.subtract(periodExpense).setScale(2, RoundingMode.HALF_UP);
        if (currentEarnings.signum() != 0) {
            totalEquity = totalEquity.add(currentEarnings);
            equity.add(new BalanceSheetResponse.LineItem("RE", "Current period earnings", currentEarnings));
        }
        sortByBsCode(assets);
        sortByBsCode(liabilities);
        sortByBsCode(equity);

        BigDecimal totalLiabAndEquity = totalLiabilities.add(totalEquity).setScale(2, RoundingMode.HALF_UP);
        boolean balanced = totalAssets.subtract(totalLiabAndEquity).abs().compareTo(MONEY_TOL) <= 0;

        return new BalanceSheetResponse(
                target,
                resolvedBranch,
                List.copyOf(assets),
                List.copyOf(liabilities),
                List.copyOf(equity),
                totalAssets.setScale(2, RoundingMode.HALF_UP),
                totalLiabilities.setScale(2, RoundingMode.HALF_UP),
                totalEquity.setScale(2, RoundingMode.HALF_UP),
                totalLiabAndEquity,
                balanced
        );
    }

    /**
     * VAT position for a period. The money figures read the ledger's VAT payable and
     * sales revenue accounts (so voids and refunds are already netted); the tax-code
     * breakdown reads completed sale lines for the period.
     */
    @Transactional(readOnly = true)
    public VatReportResponse vatReport(String businessId, LocalDate from, LocalDate to, String branchId) {
        if (from == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from is required");
        }
        LocalDate target = to != null ? to : LocalDate.now(resolveZone(businessId));
        String resolvedBranch = resolveBranch(businessId, branchId);

        BigDecimal vatCollected = BigDecimal.ZERO;
        BigDecimal vatReversed = BigDecimal.ZERO;
        BigDecimal revenueCredit = BigDecimal.ZERO;
        BigDecimal revenueDebit = BigDecimal.ZERO;
        for (AccountBalance row : journalReportRepository
                .sumByAccountForPeriod(businessId, from, target, resolvedBranch)) {
            if (LedgerAccountCodes.VAT_PAYABLE.equals(row.getCode())) {
                vatCollected = vatCollected.add(money(row.getCreditTotal()));
                vatReversed = vatReversed.add(money(row.getDebitTotal()));
            } else if (LedgerAccountCodes.SALES_REVENUE.equals(row.getCode())) {
                revenueCredit = revenueCredit.add(money(row.getCreditTotal()));
                revenueDebit = revenueDebit.add(money(row.getDebitTotal()));
            }
        }

        List<VatReportResponse.TaxCodeRow> byTaxCode = new ArrayList<>();
        for (TaxCodeVatRow row : journalReportRepository
                .sumVatByTaxCode(businessId, from, target, resolvedBranch)) {
            byTaxCode.add(new VatReportResponse.TaxCodeRow(
                    row.getTaxCode() == null ? "" : row.getTaxCode(),
                    money(row.getTaxableAmount()),
                    money(row.getTaxAmount()),
                    row.getLineCount() == null ? 0L : row.getLineCount().longValue()));
        }

        return new VatReportResponse(
                from,
                target,
                resolvedBranch,
                vatCollected.setScale(2, RoundingMode.HALF_UP),
                vatReversed.setScale(2, RoundingMode.HALF_UP),
                vatCollected.subtract(vatReversed).setScale(2, RoundingMode.HALF_UP),
                revenueCredit.subtract(revenueDebit).setScale(2, RoundingMode.HALF_UP),
                byTaxCode);
    }

    /**
     * The same VAT position as {@link #vatReport}, split into day or month buckets and
     * zero-filled across the range (mirrors {@link #dailyProfitAndLoss}). Bucket figures
     * come from the ledger, so voids and refunds are already netted.
     */
    @Transactional(readOnly = true)
    public List<VatBucketResponse> vatReportBuckets(
            String businessId,
            LocalDate from,
            LocalDate to,
            VatBucket bucket,
            String branchId
    ) {
        if (from == null || to == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from and to are required");
        }
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "to must be on or after from");
        }
        boolean monthly = bucket == VatBucket.MONTH;
        String resolvedBranch = resolveBranch(businessId, branchId);

        // acc = [vatCollected, vatReversed, revenueCredit, revenueDebit], keyed by bucket start.
        Map<LocalDate, BigDecimal[]> byBucket = new HashMap<>();
        for (JournalReportRepository.DailyAccountBalance row : journalReportRepository
                .sumByAccountForPeriodByDay(businessId, from, to, resolvedBranch)) {
            boolean vat = LedgerAccountCodes.VAT_PAYABLE.equals(row.getCode());
            boolean revenue = LedgerAccountCodes.SALES_REVENUE.equals(row.getCode());
            if (!vat && !revenue) {
                continue;
            }
            LocalDate day = toLocalDate(row.getJournalDay());
            if (day == null) {
                continue;
            }
            LocalDate key = monthly ? day.withDayOfMonth(1) : day;
            BigDecimal[] acc = byBucket.computeIfAbsent(key, k -> new BigDecimal[] {
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO });
            if (vat) {
                acc[0] = acc[0].add(money(row.getCreditTotal()));
                acc[1] = acc[1].add(money(row.getDebitTotal()));
            } else {
                acc[2] = acc[2].add(money(row.getCreditTotal()));
                acc[3] = acc[3].add(money(row.getDebitTotal()));
            }
        }

        List<VatBucketResponse> out = new ArrayList<>();
        for (LocalDate start = monthly ? from.withDayOfMonth(1) : from;
                !start.isAfter(to);
                start = monthly ? start.plusMonths(1) : start.plusDays(1)) {
            BigDecimal[] acc = byBucket.getOrDefault(start, new BigDecimal[] {
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO });
            BigDecimal collected = acc[0].setScale(2, RoundingMode.HALF_UP);
            BigDecimal reversed = acc[1].setScale(2, RoundingMode.HALF_UP);
            BigDecimal netRevenue = acc[2].subtract(acc[3]).setScale(2, RoundingMode.HALF_UP);
            LocalDate end = monthly ? start.withDayOfMonth(start.lengthOfMonth()) : start;
            out.add(new VatBucketResponse(
                    start,
                    end,
                    collected,
                    reversed,
                    collected.subtract(reversed).setScale(2, RoundingMode.HALF_UP),
                    netRevenue));
        }
        return out;
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

    private ZoneId resolveZone(String businessId) {
        Business business = businessRepository.findById(businessId).orElse(null);
        return BusinessTimeZones.of(business);
    }

    private static String blankToNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private static ProfitAndLossResponse.LineItem line(AccountBalance row, BigDecimal amount) {
        return new ProfitAndLossResponse.LineItem(row.getCode(), row.getName(), amount);
    }

    private static BalanceSheetResponse.LineItem bsLine(AccountBalance row, BigDecimal amount) {
        return new BalanceSheetResponse.LineItem(row.getCode(), row.getName(), amount);
    }

    private static void sortByCode(List<ProfitAndLossResponse.LineItem> items) {
        items.sort(Comparator.comparing(ProfitAndLossResponse.LineItem::accountCode));
    }

    private static void sortByBsCode(List<BalanceSheetResponse.LineItem> items) {
        items.sort(Comparator.comparing(BalanceSheetResponse.LineItem::accountCode));
    }

    private static BigDecimal money(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String normalizedType(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
