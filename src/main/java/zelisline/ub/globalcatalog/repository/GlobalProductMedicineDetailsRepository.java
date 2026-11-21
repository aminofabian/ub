package zelisline.ub.globalcatalog.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.globalcatalog.domain.GlobalProductMedicineDetails;

public interface GlobalProductMedicineDetailsRepository
        extends JpaRepository<GlobalProductMedicineDetails, String> {

    List<GlobalProductMedicineDetails> findByGlobalProductIdIn(Collection<String> globalProductIds);
}
