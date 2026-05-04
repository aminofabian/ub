package zelisline.ub.reporting.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import zelisline.ub.reporting.domain.MvSupplierMonthly;
import zelisline.ub.reporting.repository.MvSupplierMonthlyRepository;
import zelisline.ub.reporting.repository.MvSupplierMonthlyRepository.InvoiceMonthRollup;
import zelisline.ub.reporting.repository.MvSupplierMonthlyRepository.WastageMonthRollup;

@Component
@RequiredArgsConstructor
public class MvSupplierMonthlyRefresher implements ReportingMvRefresher {

    public static final String NAME = "mv_supplier_monthly";

    private final MvSupplierMonthlyRepository repository;

    @Override
    public String mvName() {
        return NAME;
    }

    @Override
    @Transactional
    public long refresh(String businessId) {
        repository.deleteForBusiness(businessId);
        List<InvoiceMonthRollup> inv = repository.rollupPostedInvoices(businessId);
        List<WastageMonthRollup> wastage = repository.rollupWastage(businessId);
        Instant refreshedAt = Instant.now();

        Map<String, MvSupplierMonthly> merged = new LinkedHashMap<>();
        for (InvoiceMonthRollup row : inv) {
            LocalDate cm = LocalDate.of(row.getPeriodYear(), row.getPeriodMonth(), 1);
            MvSupplierMonthly e = baseRow(businessId, row.getSupplierId(), cm, refreshedAt);
            e.setSpend(nullToZero(row.getSpend()));
            e.setQty(nullToZeroQty(row.getQty()));
            e.setInvoiceCount(row.getInvoiceCount() == null ? 0L : row.getInvoiceCount().longValue());
            e.setWastageQty(BigDecimal.ZERO);
            merged.put(key(row.getSupplierId(), cm), e);
        }
        for (WastageMonthRollup w : wastage) {
            LocalDate cm = LocalDate.of(w.getPeriodYear(), w.getPeriodMonth(), 1);
            String k = key(w.getSupplierId(), cm);
            MvSupplierMonthly e = merged.computeIfAbsent(k, kk ->
                    baseRow(businessId, w.getSupplierId(), cm, refreshedAt));
            if (e.getSpend() == null) {
                e.setSpend(BigDecimal.ZERO);
            }
            if (e.getQty() == null) {
                e.setQty(BigDecimal.ZERO);
            }
            e.setWastageQty(nullToZeroQty(w.getWastageQty()));
        }
        repository.saveAll(merged.values());
        return merged.size();
    }

    private static MvSupplierMonthly baseRow(String businessId, String supplierId, LocalDate cm, Instant refreshedAt) {
        MvSupplierMonthly e = new MvSupplierMonthly();
        MvSupplierMonthly.Key id = new MvSupplierMonthly.Key();
        id.setBusinessId(businessId);
        id.setSupplierId(supplierId);
        id.setCalendarMonth(cm);
        e.setId(id);
        e.setSpend(BigDecimal.ZERO);
        e.setQty(BigDecimal.ZERO);
        e.setInvoiceCount(0L);
        e.setWastageQty(BigDecimal.ZERO);
        e.setRefreshedAt(refreshedAt);
        return e;
    }

    private static String key(String supplierId, LocalDate cm) {
        return supplierId + "|" + cm;
    }

    private static BigDecimal nullToZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal nullToZeroQty(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
