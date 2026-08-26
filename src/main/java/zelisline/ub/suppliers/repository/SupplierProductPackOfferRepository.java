package zelisline.ub.suppliers.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.suppliers.domain.SupplierProductPackOffer;

public interface SupplierProductPackOfferRepository extends JpaRepository<SupplierProductPackOffer, String> {

    List<SupplierProductPackOffer> findBySupplierProductIdIn(Collection<String> supplierProductIds);
}
