package zelisline.ub.till.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.till.domain.TillAccessRequest;

public interface TillAccessRequestRepository extends JpaRepository<TillAccessRequest, String> {

    Optional<TillAccessRequest> findByBusinessIdAndBranchIdAndDeviceKey(
            String businessId,
            String branchId,
            String deviceKey);

    Optional<TillAccessRequest> findByIdAndBusinessId(String id, String businessId);

    List<TillAccessRequest> findByBusinessIdAndBranchIdAndStatusOrderByLastSeenAtDesc(
            String businessId,
            String branchId,
            String status);

    List<TillAccessRequest> findByBusinessIdAndStatusOrderByLastSeenAtDesc(
            String businessId,
            String status);
}
