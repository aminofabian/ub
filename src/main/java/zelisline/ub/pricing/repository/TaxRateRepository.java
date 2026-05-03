package zelisline.ub.pricing.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.pricing.domain.TaxRate;

public interface TaxRateRepository extends JpaRepository<TaxRate, String> {

    List<TaxRate> findByBusinessIdAndActiveIsTrueOrderByNameAsc(String businessId);
}
