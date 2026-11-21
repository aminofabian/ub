package zelisline.ub.clinic.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.clinic.domain.PrescriptionLine;

public interface PrescriptionLineRepository extends JpaRepository<PrescriptionLine, String> {

    List<PrescriptionLine> findByBusinessIdAndPrescriptionIdOrderByLineIndexAsc(String businessId, String prescriptionId);

    List<PrescriptionLine> findByBusinessIdAndPrescriptionIdInOrderByLineIndexAsc(
            String businessId, Collection<String> prescriptionIds);
}
