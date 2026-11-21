package zelisline.ub.clinic.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.clinic.domain.Prescription;

public interface PrescriptionRepository extends JpaRepository<Prescription, String> {

    /** The live prescription of a visit: a visit has at most one that has not been superseded. */
    Optional<Prescription> findFirstByBusinessIdAndVisitIdOrderByCreatedAtDescIdDesc(String businessId, String visitId);

    List<Prescription> findByBusinessIdAndVisitIdIn(String businessId, Collection<String> visitIds);
}
