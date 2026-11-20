package zelisline.ub.pharmacy.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.pharmacy.domain.PharmacyPatient;

public interface PharmacyPatientRepository extends JpaRepository<PharmacyPatient, String> {

    Optional<PharmacyPatient> findByIdAndBusinessIdAndDeletedAtIsNull(String id, String businessId);

    List<PharmacyPatient> findByBusinessIdAndIdIn(String businessId, Collection<String> ids);

    List<PharmacyPatient> findByBusinessIdAndPhoneAndDeletedAtIsNull(String businessId, String phone);

    /** {@code pattern} is a lower-case LIKE pattern, or "%" for everyone. */
    @Query("""
            select p from PharmacyPatient p
             where p.businessId = :businessId
               and p.deletedAt is null
               and (lower(p.name) like :pattern or p.phone like :pattern)
             order by p.name asc, p.id asc
            """)
    List<PharmacyPatient> search(
            @Param("businessId") String businessId, @Param("pattern") String pattern, Pageable pageable);

    @Query("""
            select count(p) from PharmacyPatient p
             where p.businessId = :businessId
               and p.deletedAt is null
               and (lower(p.name) like :pattern or p.phone like :pattern)
            """)
    long countSearch(@Param("businessId") String businessId, @Param("pattern") String pattern);
}
