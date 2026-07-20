package zelisline.ub.reporting.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.reporting.api.dto.SalesRegisterResponse;
import zelisline.ub.reporting.repository.MvSalesDailyRepository;
import zelisline.ub.reporting.repository.MvSalesDailyRepository.DailyRollup;
import zelisline.ub.tenancy.repository.BranchRepository;

/**
 * Phase 7 Slice 2 read facade for the Sales register report (#2). Hybrid by design:
 * past days prefer {@code mv_sales_daily}, with OLTP gap-fill when the MV has not been
 * refreshed yet; today always comes from OLTP. The composition lives here so controllers
 * and exporters stay thin.
 */
@Service
@RequiredArgsConstructor
public class SalesReportsService {

    private static final BigDecimal QTY_ZERO = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    private static final BigDecimal MONEY_ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final MvSalesDailyRepository mvRepository;
    private final BranchRepository branchRepository;

    @Transactional(readOnly = true)
    public SalesRegisterResponse salesRegister(String businessId, LocalDate from, LocalDate to, String branchId) {
        return salesRegister(businessId, from, to, branchId, null);
    }

    @Transactional(readOnly = true)
    public SalesRegisterResponse salesRegister(
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
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        // Past window: [from, min(to, today-1)] — MV first, OLTP fills missing day×branch rows
        // so a stale/empty rollup does not blank the seven-day runway.
        LocalDate mvUpper = to.isBefore(today) ? to : today.minusDays(1);
        List<SalesRegisterResponse.Day> days = new ArrayList<>();
        Set<String> covered = new HashSet<>();
        if (!mvUpper.isBefore(from)) {
            for (DailyRollup r : mvRepository.sumByDay(
                    businessId, from, mvUpper, resolvedBranch, resolvedType)) {
                days.add(map(r));
                covered.add(dayBranchKey(r));
            }
            for (DailyRollup r : mvRepository.sumOltpByDay(
                    businessId, from, mvUpper, resolvedBranch, resolvedType)) {
                if (covered.add(dayBranchKey(r))) {
                    days.add(map(r));
                }
            }
        }
        // OLTP side: today, only if the request spans into today.
        if (!today.isBefore(from) && !today.isAfter(to)) {
            mvRepository.sumOltpForDay(businessId, today, resolvedBranch, resolvedType)
                    .forEach(r -> days.add(map(r)));
        }

        days.sort(Comparator
                .comparing(SalesRegisterResponse.Day::day)
                .thenComparing(SalesRegisterResponse.Day::branchId));

        BigDecimal totalQty = QTY_ZERO;
        BigDecimal totalRevenue = MONEY_ZERO;
        BigDecimal totalCost = MONEY_ZERO;
        BigDecimal totalProfit = MONEY_ZERO;
        for (SalesRegisterResponse.Day d : days) {
            totalQty = totalQty.add(d.qty());
            totalRevenue = totalRevenue.add(d.revenue());
            totalCost = totalCost.add(d.cost());
            totalProfit = totalProfit.add(d.profit());
        }

        return new SalesRegisterResponse(
                from,
                to,
                resolvedBranch,
                List.copyOf(days),
                totalQty.setScale(4, RoundingMode.HALF_UP),
                totalRevenue.setScale(2, RoundingMode.HALF_UP),
                totalCost.setScale(2, RoundingMode.HALF_UP),
                totalProfit.setScale(2, RoundingMode.HALF_UP)
        );
    }

    private SalesRegisterResponse.Day map(DailyRollup r) {
        return new SalesRegisterResponse.Day(
                r.getBusinessDay(),
                r.getBranchId(),
                money4(r.getQty()),
                money2(r.getRevenue()),
                money2(r.getCost()),
                money2(r.getProfit())
        );
    }

    private static String dayBranchKey(DailyRollup r) {
        return r.getBusinessDay() + "|" + r.getBranchId();
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

    private static String blankToNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private static BigDecimal money4(BigDecimal v) {
        return v == null ? QTY_ZERO : v.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal money2(BigDecimal v) {
        return v == null ? MONEY_ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }
}
