package zelisline.ub.pricing.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.pricing.domain.TaxRate;

public interface TaxRateRepository extends JpaRepository<TaxRate, String> {

    List<TaxRate> findByBusinessIdAndActiveIsTrueOrderByNameAsc(String businessId);

    List<TaxRate> findByBusinessIdOrderByNameAsc(String businessId);

    Optional<TaxRate> findByIdAndBusinessId(String id, String businessId);

    boolean existsByBusinessIdAndName(String businessId, String name);

    boolean existsByBusinessIdAndNameAndIdNot(String businessId, String name, String id);

    @Query("SELECT t FROM TaxRate t WHERE t.businessId = :businessId AND t.id IN :ids")
    List<TaxRate> findByBusinessIdAndIdIn(@Param("businessId") String businessId, @Param("ids") Collection<String> ids);
}
