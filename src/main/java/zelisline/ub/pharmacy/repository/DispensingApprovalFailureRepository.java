package zelisline.ub.pharmacy.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.pharmacy.domain.DispensingApprovalFailure;

public interface DispensingApprovalFailureRepository extends JpaRepository<DispensingApprovalFailure, String> {

    long countByBusinessIdAndApproverUserIdAndCreatedAtAfter(
            String businessId, String approverUserId, Instant after);

    long countByBusinessIdAndRequestedByAndCreatedAtAfter(
            String businessId, String requestedBy, Instant after);
}
