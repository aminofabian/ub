package zelisline.ub.pharmacy.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.pharmacy.domain.PharmacySchemeClaim;

public interface PharmacySchemeClaimRepository extends JpaRepository<PharmacySchemeClaim, String> {

    Optional<PharmacySchemeClaim> findByIdAndBusinessId(String id, String businessId);

    List<PharmacySchemeClaim> findByBusinessIdOrderByCreatedAtDescIdDesc(String businessId, Pageable pageable);

    List<PharmacySchemeClaim> findByBusinessIdAndRecordId(String businessId, String recordId);
}
