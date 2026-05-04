package zelisline.ub.reporting.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.reporting.api.dto.SupplierMonthlySpendResponse;
import zelisline.ub.reporting.repository.MvSupplierMonthlyRepository;
import zelisline.ub.reporting.repository.MvSupplierMonthlyRepository.MonthlySupplierAgg;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;

@Service
@RequiredArgsConstructor
public class SupplierReportsService {

    private static final BigDecimal QTY_ZERO = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    private static final BigDecimal MONEY_ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final MvSupplierMonthlyRepository mvSupplierMonthlyRepository;
    private final SupplierRepository supplierRepository;

    @Transactional(readOnly = true)
    public SupplierMonthlySpendResponse monthlySpend(String businessId, LocalDate fromMonth, LocalDate toMonth) {
        LocalDate from = firstDayOfMonth(fromMonth);
        LocalDate to = firstDayOfMonth(toMonth);
        if (from == null || to == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fromMonth and toMonth are required");
        }
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "toMonth must be on or after fromMonth");
        }

        YearMonth ymFrom = YearMonth.from(from);
        YearMonth ymTo = YearMonth.from(to);
        YearMonth ymNow = YearMonth.now(ZoneOffset.UTC);
        YearMonth lastClosed = ymNow.minusMonths(1);

        List<SupplierMonthlySpendResponse.Row> out = new ArrayList<>();
        BigDecimal totalSpend = MONEY_ZERO;
        BigDecimal totalQty = QTY_ZERO;

        YearMonth mvEnd = ymTo.isAfter(lastClosed) ? lastClosed : ymTo;
        if (!mvEnd.isBefore(ymFrom)) {
            LocalDate mvFromDate = ymFrom.atDay(1);
            LocalDate mvToMonthStart = mvEnd.atDay(1);
            List<MonthlySupplierAgg> mvRows = mvSupplierMonthlyRepository.listMvRange(
                    businessId, mvFromDate, mvToMonthStart);
            for (MonthlySupplierAgg r : mvRows) {
                var row = toRow(businessId, r);
                out.add(row);
                totalSpend = totalSpend.add(row.spend());
                totalQty = totalQty.add(row.qty());
            }
        }

        if (!ymTo.isBefore(ymNow) && !ymFrom.isAfter(ymNow)) {
            for (MonthlySupplierAgg r : mvSupplierMonthlyRepository.listOltpForMonth(businessId, ymNow.atDay(1))) {
                SupplierMonthlySpendResponse.Row row = toRow(businessId, r);
                out.add(row);
                totalSpend = totalSpend.add(row.spend());
                totalQty = totalQty.add(row.qty());
            }
        }

        out.sort(Comparator.comparing(SupplierMonthlySpendResponse.Row::calendarMonth)
                .thenComparing(SupplierMonthlySpendResponse.Row::supplierName));

        return new SupplierMonthlySpendResponse(
                from,
                to,
                List.copyOf(out),
                totalSpend.setScale(2, RoundingMode.HALF_UP),
                totalQty.setScale(4, RoundingMode.HALF_UP)
        );
    }

    private SupplierMonthlySpendResponse.Row toRow(String businessId, MonthlySupplierAgg r) {
        String name = supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(r.getSupplierId(), businessId)
                .map(Supplier::getName)
                .orElse("(unknown supplier)");
        return new SupplierMonthlySpendResponse.Row(
                r.getSupplierId(),
                name,
                r.getCalendarMonth(),
                money2(r.getSpend()),
                qty4(r.getQty()),
                r.getInvoiceCount(),
                qty4(r.getWastageQty())
        );
    }

    private static LocalDate firstDayOfMonth(LocalDate d) {
        if (d == null) {
            return null;
        }
        return YearMonth.from(d).atDay(1);
    }

    private static BigDecimal money2(BigDecimal v) {
        return v == null ? MONEY_ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal qty4(BigDecimal v) {
        return v == null ? QTY_ZERO : v.setScale(4, RoundingMode.HALF_UP);
    }

}
