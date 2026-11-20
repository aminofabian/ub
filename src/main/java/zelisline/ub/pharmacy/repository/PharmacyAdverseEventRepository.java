package zelisline.ub.pharmacy.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.pharmacy.domain.PharmacyAdverseEvent;

public interface PharmacyAdverseEventRepository extends JpaRepository<PharmacyAdverseEvent, String> {

    List<PharmacyAdverseEvent> findByBusinessIdOrderByCreatedAtDescIdDesc(String businessId, Pageable pageable);

    Optional<PharmacyAdverseEvent> findByIdAndBusinessId(String id, String businessId);
}
