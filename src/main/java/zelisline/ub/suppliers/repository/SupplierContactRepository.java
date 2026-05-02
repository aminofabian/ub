package zelisline.ub.suppliers.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.suppliers.domain.SupplierContact;

public interface SupplierContactRepository extends JpaRepository<SupplierContact, String> {

    List<SupplierContact> findBySupplierIdOrderByPrimaryContactDescNameAsc(String supplierId);

    Optional<SupplierContact> findByIdAndSupplierId(String id, String supplierId);
}
