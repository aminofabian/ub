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

    /** Every profile on a phone number: a parent and children often share one. */
    List<PharmacyPatient> findByBusinessIdAndPhoneAndDeletedAtIsNullOrderByNameAscIdAsc(
            String businessId, String phone);

    Optional<PharmacyPatient> findByBusinessIdAndPatientNoAndDeletedAtIsNull(String businessId, Long patientNo);

    /** Highest number the business has used, removed profiles included; 0 when it has none. */
    @Query("select coalesce(max(p.patientNo), 0) from PharmacyPatient p where p.businessId = :businessId")
    long maxPatientNo(@Param("businessId") String businessId);

    /**
     * {@code pattern} is a lower-case LIKE pattern, or "%" for everyone. {@code patientNo} is the
     * number typed, or -1 when the search is not a patient number.
     */
    @Query("""
            select p from PharmacyPatient p
             where p.businessId = :businessId
               and p.deletedAt is null
               and (lower(p.name) like :pattern or p.phone like :pattern or p.patientNo = :patientNo)
             order by p.name asc, p.id asc
            """)
    List<PharmacyPatient> search(
            @Param("businessId") String businessId,
            @Param("pattern") String pattern,
            @Param("patientNo") long patientNo,
            Pageable pageable);

    @Query("""
            select count(p) from PharmacyPatient p
             where p.businessId = :businessId
               and p.deletedAt is null
               and (lower(p.name) like :pattern or p.phone like :pattern or p.patientNo = :patientNo)
            """)
    long countSearch(
            @Param("businessId") String businessId,
            @Param("pattern") String pattern,
            @Param("patientNo") long patientNo);
}
