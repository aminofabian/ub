package zelisline.ub.reporting.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.reporting.repository.MvSalesDailyRepository;

/**
 * Phase 7 Slice 2 refresher. v1 strategy is delete-then-insert per tenant inside one
 * transaction (PHASE_7_PLAN.md Locked ADRs: scheduled full refresh, no incremental
 * outbox cursor in v1). The whole-tenant scan is acceptable for v1 fixture sizes;
 * incremental refresh by date window is a Phase 7.1+ optimisation.
 */
@Component
@RequiredArgsConstructor
public class MvSalesDailyRefresher implements ReportingMvRefresher {

    public static final String NAME = "mv_sales_daily";

    private final MvSalesDailyRepository repository;

    @Override
    public String mvName() {
        return NAME;
    }

    @Override
    @Transactional
    public long refresh(String businessId) {
        repository.deleteForBusiness(businessId);
        return repository.rebuildForBusiness(businessId);
    }
}
