package zelisline.ub.pharmacy.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.pharmacy.domain.PharmacyProfile;

public interface PharmacyProfileRepository extends JpaRepository<PharmacyProfile, String> {

    /** Profiles whose licence expires on or before the given day (reminder sweep; cross-tenant by design). */
    List<PharmacyProfile> findByLicenceExpiresOnIsNotNullAndLicenceExpiresOnLessThanEqual(LocalDate day);
}
