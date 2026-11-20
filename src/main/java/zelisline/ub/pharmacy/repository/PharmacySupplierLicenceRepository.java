package zelisline.ub.pharmacy.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.pharmacy.domain.PharmacySupplierLicence;

public interface PharmacySupplierLicenceRepository extends JpaRepository<PharmacySupplierLicence, String> {

    List<PharmacySupplierLicence> findByBusinessId(String businessId);

    Optional<PharmacySupplierLicence> findByBusinessIdAndSupplierId(String businessId, String supplierId);
}
