package zelisline.ub.reporting.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.reporting.repository.MvInventorySnapshotRepository;

@Component
@RequiredArgsConstructor
public class MvInventorySnapshotRefresher implements ReportingMvRefresher {

    public static final String NAME = "mv_inventory_snapshot";

    private final MvInventorySnapshotRepository repository;

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
