package zelisline.ub.pharmacy.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.pharmacy.domain.PharmacyTemperatureLog;

public interface PharmacyTemperatureLogRepository extends JpaRepository<PharmacyTemperatureLog, String> {

    List<PharmacyTemperatureLog> findByBusinessIdAndRecordedAtGreaterThanEqualOrderByRecordedAtDescIdDesc(
            String businessId, Instant since, Pageable pageable);

    List<PharmacyTemperatureLog> findByBusinessIdAndBranchIdAndRecordedAtGreaterThanEqualOrderByRecordedAtDescIdDesc(
            String businessId, String branchId, Instant since, Pageable pageable);
}
