package zelisline.ub.finance.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.finance.domain.CashDrawerDailySummary;
import zelisline.ub.finance.repository.CashDrawerDailySummaryRepository;
import zelisline.ub.finance.repository.ExpenseRepository;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.repository.RefundPaymentRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;

@Service
@RequiredArgsConstructor
public class CashDrawerSummaryService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final CashDrawerDailySummaryRepository summaryRepository;
    private final SalePaymentRepository salePaymentRepository;
    private final RefundPaymentRepository refundPaymentRepository;
    private final ExpenseRepository expenseRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void upsertForClosedShift(Shift shift) {
        BigDecimal opening = money(shift.getOpeningCash());
        BigDecimal cashSales = money(salePaymentRepository.sumCashTenderForShiftWindow(
                shift.getBusinessId(), shift.getBranchId(), shift.getId(), shift.getOpenedAt(), shift.getClosedAt()));
        BigDecimal cashRefunds = money(refundPaymentRepository.sumCashRefundForShiftWindow(
                shift.getBusinessId(), shift.getBranchId(), shift.getId(), shift.getOpenedAt(), shift.getClosedAt()));
        BigDecimal drawerExpenses = money(expenseRepository.sumDrawerCashExpensesForShiftWindow(
                shift.getBusinessId(), shift.getBranchId(), shift.getOpenedAt(), shift.getClosedAt()));
        BigDecimal supplierCashFromDrawer = ZERO; // Phase 6.3 baseline; supplier drawer attribution lands in a follow-up.
        BigDecimal expected = money(shift.getExpectedClosingCash());
        BigDecimal counted = shift.getCountedClosingCash() == null ? null : money(shift.getCountedClosingCash());
        BigDecimal variance = shift.getClosingVariance() == null ? null : money(shift.getClosingVariance());

        CashDrawerDailySummary row = summaryRepository.findByShiftId(shift.getId())
                .orElseGet(CashDrawerDailySummary::new);
        row.setBusinessId(shift.getBusinessId());
        row.setBranchId(shift.getBranchId());
        row.setShiftId(shift.getId());
        row.setBusinessDate(LocalDate.ofInstant(shift.getClosedAt(), ZoneOffset.UTC));
        row.setOpeningCash(opening);
        row.setCashSales(cashSales);
        row.setCashRefunds(cashRefunds);
        row.setDrawerExpenses(drawerExpenses);
        row.setSupplierCashFromDrawer(supplierCashFromDrawer);
        row.setExpectedClosingCash(expected);
        row.setCountedClosingCash(counted);
        row.setClosingVariance(variance);
        row.setSnapshotJson(snapshotJson(opening, cashSales, cashRefunds, drawerExpenses, supplierCashFromDrawer, expected, counted,
                variance));
        summaryRepository.save(row);
    }

    private String snapshotJson(
            BigDecimal opening,
            BigDecimal cashSales,
            BigDecimal cashRefunds,
            BigDecimal drawerExpenses,
            BigDecimal supplierCashFromDrawer,
            BigDecimal expected,
            BigDecimal counted,
            BigDecimal variance
    ) {
        try {
            return objectMapper.writeValueAsString(new Snapshot(
                    opening,
                    cashSales,
                    cashRefunds,
                    drawerExpenses,
                    supplierCashFromDrawer,
                    expected,
                    counted,
                    variance
            ));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BigDecimal money(BigDecimal value) {
        if (value == null) {
            return ZERO;
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private record Snapshot(
            BigDecimal openingCash,
            BigDecimal cashSales,
            BigDecimal cashRefunds,
            BigDecimal drawerExpenses,
            BigDecimal supplierCashFromDrawer,
            BigDecimal expectedClosingCash,
            BigDecimal countedClosingCash,
            BigDecimal closingVariance
    ) {
    }
}

