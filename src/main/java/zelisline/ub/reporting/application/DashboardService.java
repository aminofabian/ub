package zelisline.ub.reporting.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.finance.application.FinanceReportsService;
import zelisline.ub.purchasing.application.SupplierPaymentService;
import zelisline.ub.reporting.api.dto.OwnerDashboardResponse;
import zelisline.ub.reporting.repository.MvSalesDailyRepository;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final int TOP_SKU_LIMIT = 5;

    private final FinanceReportsService financeReportsService;
    private final SupplierPaymentService supplierPaymentService;
    private final MvSalesDailyRepository mvSalesDailyRepository;
    private final ItemRepository itemRepository;

    @Transactional(readOnly = true)
    public OwnerDashboardResponse ownerSummary(String businessId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = today.minusDays(30);

        var pulse = financeReportsService.pulse(businessId, today, null);
        var ap = supplierPaymentService.apAging(businessId, today, null);

        List<OwnerDashboardResponse.TopSkuByRevenue> top = new ArrayList<>();
        for (MvSalesDailyRepository.ItemRevenue row : mvSalesDailyRepository.topItemsByRevenue(
                businessId, from, today, TOP_SKU_LIMIT)) {
            BigDecimal rev = row.getRevenue() == null
                    ? BigDecimal.ZERO
                    : row.getRevenue().setScale(2, RoundingMode.HALF_UP);
            String name = itemRepository.findById(row.getItemId())
                    .filter(i -> businessId.equals(i.getBusinessId()))
                    .map(Item::getName)
                    .orElse("(unknown item)");
            top.add(new OwnerDashboardResponse.TopSkuByRevenue(row.getItemId(), name, rev));
        }
        return new OwnerDashboardResponse(pulse, ap, List.copyOf(top));
    }
}
