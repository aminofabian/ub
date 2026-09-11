package zelisline.ub.till.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.till.domain.TillAccessRequest;

public interface TillAccessRequestRepository extends JpaRepository<TillAccessRequest, String> {

    Optional<TillAccessRequest> findByBusinessIdAndBranchIdAndDeviceKey(
            String businessId,
            String branchId,
            String deviceKey);
}
