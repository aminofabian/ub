package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.api.dto.BranchValuationLine;
import zelisline.ub.inventory.api.dto.InventoryValuationResponse;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.repository.BranchRepository;

@Service
@RequiredArgsConstructor
public class InventoryValuationService {

    private static final int MONEY_SCALE = 2;

    private final InventoryBatchRepository inventoryBatchRepository;
    private final BranchRepository branchRepository;

    @Transactional(readOnly = true)
    public InventoryValuationResponse valuation(String businessId, String branchIdFilter) {
        String branchId = blankToNull(branchIdFilter);
        if (branchId != null) {
            branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
        }
        String status = InventoryConstants.BATCH_STATUS_ACTIVE;
        Map<String, String> branchNames = branchRepository
                .findByBusinessIdAndDeletedAtIsNullOrderByNameAsc(businessId)
                .stream()
                .collect(Collectors.toMap(Branch::getId, Branch::getName));
        List<BranchValuationLine> lines = new ArrayList<>();
        for (Object[] row : inventoryBatchRepository.sumExtensionValueByBranch(businessId, status, branchId)) {
            String bid = (String) row[0];
            BigDecimal ext = toBigDecimal(row[1]).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            String name = branchNames.getOrDefault(bid, bid);
            lines.add(new BranchValuationLine(bid, name, ext));
        }
        BigDecimal total = inventoryBatchRepository.sumTotalExtensionValue(businessId, status, branchId);
        if (total == null) {
            total = BigDecimal.ZERO;
        }
        total = total.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return new InventoryValuationResponse(businessId, List.copyOf(lines), total);
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s;
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return BigDecimal.ZERO;
    }
}
