package zelisline.ub.pharmacy.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.pharmacy.domain.DispensingRecordCorrection;

public interface DispensingRecordCorrectionRepository extends JpaRepository<DispensingRecordCorrection, String> {

    List<DispensingRecordCorrection> findByBusinessIdAndRecordIdInOrderByCreatedAtAscIdAsc(
            String businessId, Collection<String> recordIds);
}
