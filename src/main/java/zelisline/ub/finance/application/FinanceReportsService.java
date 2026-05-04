package zelisline.ub.finance.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.api.dto.BalanceSheetResponse;
import zelisline.ub.finance.api.dto.FinancePulseResponse;
import zelisline.ub.finance.api.dto.ProfitAndLossResponse;
import zelisline.ub.finance.repository.JournalReportRepository;
import zelisline.ub.finance.repository.JournalReportRepository.AccountBalance;
import zelisline.ub.finance.repository.JournalReportRepository.SaleAggregate;
import zelisline.ub.tenancy.repository.BranchRepository;

/**
 * Owner pulse, simple P&amp;L, and simple balance sheet — the Phase 6 close-out gate
 * Phase 7 Slice 0 promises before MV optimisation begins. All three are journal-backed
 * (with sales/expenses cross-checks for pulse "today") so Phase 7 Slices 2+ can layer MVs
 * over the same numbers without behavioural drift.
 */
@Service
@RequiredArgsConstructor
public class FinanceReportsService {

    private static final BigDecimal MONEY_TOL = new BigDecimal("0.01");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final JournalReportRepository journalReportRepository;
    private final BranchRepository branchRepository;

    @Transactional(readOnly = true)
    public FinancePulseResponse pulse(String businessId, LocalDate date, String branchId) {
        LocalDate day = date != null ? date : LocalDate.now(ZoneOffset.UTC);
        String resolvedBranch = resolveBranch(businessId, branchId);

        SaleAggregate sales = journalReportRepository.sumSalesForWindow(
                businessId,
                day.atStartOfDay(ZoneOffset.UTC).toInstant(),
                day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                resolvedBranch
        );
        BigDecimal revenue = money(sales != null ? sales.getRevenue() : null);
        BigDecimal cogs = money(sales != null ? sales.getCogs() : null);
        BigDecimal profit = money(sales != null ? sales.getProfit() : null);
        long saleCount = sales != null ? sales.getSaleCount() : 0L;

        BigDecimal expensesTotal = money(journalReportRepository.sumExpensesForDate(businessId, day, resolvedBranch));
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
        if (from == null || to == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from and to are required");
        }
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "to must be on or after from");
        }
        String resolvedBranch = resolveBranch(businessId, branchId);

        List<AccountBalance> rows = journalReportRepository.sumByAccountForPeriod(businessId, from, to);
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

    @Transactional(readOnly = true)
    public BalanceSheetResponse balanceSheet(String businessId, LocalDate asOf, String branchId) {
        LocalDate target = asOf != null ? asOf : LocalDate.now(ZoneOffset.UTC);
        String resolvedBranch = resolveBranch(businessId, branchId);

        List<AccountBalance> rows = journalReportRepository.sumByAccountAsOf(businessId, target);
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

    private String resolveBranch(String businessId, String branchId) {
        if (branchId == null || branchId.isBlank()) {
            return null;
        }
        String trimmed = branchId.trim();
        branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(trimmed, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
        return trimmed;
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
