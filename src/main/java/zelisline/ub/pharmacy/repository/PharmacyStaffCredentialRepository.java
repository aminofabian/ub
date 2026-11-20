package zelisline.ub.pharmacy.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.pharmacy.domain.PharmacyStaffCredential;

public interface PharmacyStaffCredentialRepository extends JpaRepository<PharmacyStaffCredential, String> {

    Optional<PharmacyStaffCredential> findByUserIdAndBusinessId(String userId, String businessId);

    List<PharmacyStaffCredential> findByBusinessIdAndUserIdIn(String businessId, Collection<String> userIds);

    List<PharmacyStaffCredential> findByBusinessId(String businessId);
}
