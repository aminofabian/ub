package zelisline.ub.pharmacy.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.pharmacy.domain.PharmacyScheme;

public interface PharmacySchemeRepository extends JpaRepository<PharmacyScheme, String> {

    List<PharmacyScheme> findByBusinessIdOrderByNameAsc(String businessId);

    Optional<PharmacyScheme> findByIdAndBusinessId(String id, String businessId);
}
