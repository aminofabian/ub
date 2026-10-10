package zelisline.ub.crm.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.crm.domain.CrmTag;

public interface CrmTagRepository extends JpaRepository<CrmTag, String> {

    List<CrmTag> findByBusinessIdOrderByNameAsc(String businessId);

    Optional<CrmTag> findByIdAndBusinessId(String id, String businessId);

    boolean existsByBusinessIdAndName(String businessId, String name);
}
