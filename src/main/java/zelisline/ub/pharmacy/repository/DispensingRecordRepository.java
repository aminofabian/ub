package zelisline.ub.pharmacy.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.pharmacy.domain.DispensingRecord;

public interface DispensingRecordRepository extends JpaRepository<DispensingRecord, String> {

    Optional<DispensingRecord> findByIdAndBusinessId(String id, String businessId);

    List<DispensingRecord> findByBusinessIdAndSaleId(String businessId, String saleId);

    /** Records not yet checked by a second person (voided ones left out), since a point in time. */
    long countByBusinessIdAndVerifiedAtIsNullAndVoidedAtIsNullAndCreatedAtGreaterThanEqual(
            String businessId, java.time.Instant since);

    long countByBusinessIdAndBranchIdAndVerifiedAtIsNullAndVoidedAtIsNullAndCreatedAtGreaterThanEqual(
            String businessId, String branchId, java.time.Instant since);

    List<DispensingRecord> findByBusinessIdAndSaleIdIn(String businessId, java.util.Collection<String> saleIds);

    List<DispensingRecord> findByBusinessIdAndIdIn(String businessId, java.util.Collection<String> ids);

    List<DispensingRecord> findByBusinessIdAndPatientIdOrderByCreatedAtDescIdDesc(
            String businessId, String patientId, org.springframework.data.domain.Pageable pageable);

    @Query("""
            select r
              from DispensingRecord r
             where r.businessId = :businessId
               and r.createdAt >= :from
               and r.createdAt < :to
               and (:branchId is null or r.branchId = :branchId)
             order by r.createdAt desc, r.id asc
            """)
    List<DispensingRecord> search(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable
    );

    @Query("""
            select count(r)
              from DispensingRecord r
             where r.businessId = :businessId
               and r.createdAt >= :from
               and r.createdAt < :to
               and (:branchId is null or r.branchId = :branchId)
            """)
    long countSearch(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("from") Instant from,
            @Param("to") Instant to
    );
}
