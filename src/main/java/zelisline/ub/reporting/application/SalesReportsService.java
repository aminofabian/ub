package zelisline.ub.reporting.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

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
 * past days come from {@code mv_sales_daily}, today comes from OLTP. The composition
 * lives here so controllers and exporters stay thin and so the unit test can assert
 * "MV + today = OLTP control query" on a small fixture.
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
        if (from == null || to == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from and to are required");
        }
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "to must be on or after from");
        }
        String resolvedBranch = resolveBranch(businessId, branchId);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        // MV side: covers any past day in [from, min(to, today-1)].
        LocalDate mvUpper = to.isBefore(today) ? to : today.minusDays(1);
        List<SalesRegisterResponse.Day> days = new ArrayList<>();
        if (!mvUpper.isBefore(from)) {
            mvRepository.sumByDay(businessId, from, mvUpper, resolvedBranch).forEach(r -> days.add(map(r)));
        }
        // OLTP side: today, only if the request spans into today.
        if (!today.isBefore(from) && !today.isAfter(to)) {
            mvRepository.sumOltpForDay(businessId, today, resolvedBranch).forEach(r -> days.add(map(r)));
        }

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

    private String resolveBranch(String businessId, String branchId) {
        if (branchId == null || branchId.isBlank()) {
            return null;
        }
        String trimmed = branchId.trim();
        branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(trimmed, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
        return trimmed;
    }

    private static BigDecimal money4(BigDecimal v) {
        return v == null ? QTY_ZERO : v.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal money2(BigDecimal v) {
        return v == null ? MONEY_ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }
}
