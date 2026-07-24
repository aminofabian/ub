package zelisline.ub.purchasing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zelisline.ub.inventory.repository.SupplyBatchExpenseRepository;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.domain.SupplierInvoice;

/**
 * Extra costs (transport, handling, …) live on {@code supply_batch_expenses}.
 * Path B AP totals must include them so TOTAL / BALANCE / payments match the
 * receive-stock payable.
 */
@Service
@RequiredArgsConstructor
public class PathBAssociatedCostService {

    private final SupplyBatchExpenseRepository supplyBatchExpenseRepository;

    public Map<String, BigDecimal> sumBySessionIds(String businessId, Collection<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = supplyBatchExpenseRepository.sumAmountGroupedBySourceId(
                businessId,
                PurchasingConstants.BATCH_SOURCE_PATH_B,
                sessionIds);
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<String, BigDecimal> out = new HashMap<>();
        for (Object[] row : rows) {
            if (row == null || row.length < 2 || row[0] == null) {
                continue;
            }
            BigDecimal amount = row[1] instanceof BigDecimal bd ? bd : BigDecimal.ZERO;
            out.put(String.valueOf(row[0]), amount.setScale(2, RoundingMode.HALF_UP));
        }
        return Collections.unmodifiableMap(out);
    }

    public BigDecimal sumForSession(String businessId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return sumBySessionIds(businessId, List.of(sessionId.trim()))
                .getOrDefault(sessionId.trim(), BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
    }

    /** Invoice line total plus Path B batch extras (0 when not a Path B invoice). */
    public BigDecimal payableGrandTotal(String businessId, SupplierInvoice inv) {
        BigDecimal lines = nz(inv.getGrandTotal()).setScale(2, RoundingMode.HALF_UP);
        String sessionId = inv.getRawPurchaseSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return lines;
        }
        return lines.add(sumForSession(businessId, sessionId)).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
