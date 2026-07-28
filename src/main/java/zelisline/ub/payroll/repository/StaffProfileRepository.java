package zelisline.ub.payroll.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.payroll.domain.StaffProfile;

public interface StaffProfileRepository extends JpaRepository<StaffProfile, String> {

    Optional<StaffProfile> findByBusinessIdAndUserId(String businessId, String userId);

    Optional<StaffProfile> findByIdAndBusinessId(String id, String businessId);

    List<StaffProfile> findByBusinessIdOrderByDisplayNameAsc(String businessId);
}
