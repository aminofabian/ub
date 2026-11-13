package zelisline.ub.serviceops.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.serviceops.domain.StaffLedgerEntry;

public interface StaffLedgerEntryRepository extends JpaRepository<StaffLedgerEntry, String> {

    boolean existsByBusinessIdAndDedupeKey(String businessId, String dedupeKey);

    Optional<StaffLedgerEntry> findByBusinessIdAndDedupeKey(String businessId, String dedupeKey);

    Optional<StaffLedgerEntry> findByIdAndBusinessId(String id, String businessId);

    List<StaffLedgerEntry> findByBusinessIdAndSaleIdOrderByCreatedAtAsc(String businessId, String saleId);

    List<StaffLedgerEntry> findByBusinessIdAndSaleItemIdOrderByCreatedAtAsc(String businessId, String saleItemId);

    List<StaffLedgerEntry> findByBusinessIdAndReversesId(String businessId, String reversesId);

    @Query("""
            select e from StaffLedgerEntry e
            where e.businessId = :businessId and e.staffProfileId = :staffProfileId
              and e.occurredAt >= :from and e.occurredAt < :to
            order by e.occurredAt asc, e.createdAt asc
            """)
    List<StaffLedgerEntry> findForStaffBetween(
            @Param("businessId") String businessId,
            @Param("staffProfileId") String staffProfileId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /** [staffProfileId, bucket, sum(amount)] for everything before {@code before}. */
    @Query("""
            select e.staffProfileId, e.bucket, coalesce(sum(e.amount), 0)
            from StaffLedgerEntry e
            where e.businessId = :businessId and e.occurredAt < :before
            group by e.staffProfileId, e.bucket
            """)
    List<Object[]> sumByStaffAndBucketBefore(
            @Param("businessId") String businessId, @Param("before") Instant before);

    /** [staffProfileId, kind, bucket, source, sum(amount), sum(baseAmount), count] within the period. */
    @Query("""
            select e.staffProfileId, e.kind, e.bucket, e.source,
                   coalesce(sum(e.amount), 0), coalesce(sum(e.baseAmount), 0), count(e)
            from StaffLedgerEntry e
            where e.businessId = :businessId and e.occurredAt >= :from and e.occurredAt < :to
            group by e.staffProfileId, e.kind, e.bucket, e.source
            """)
    List<Object[]> sumByStaffKindBetween(
            @Param("businessId") String businessId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("""
            select coalesce(sum(e.amount), 0) from StaffLedgerEntry e
            where e.businessId = :businessId and e.staffProfileId = :staffProfileId and e.bucket = :bucket
            """)
    BigDecimal balance(
            @Param("businessId") String businessId,
            @Param("staffProfileId") String staffProfileId,
            @Param("bucket") String bucket);

    @Query("select coalesce(sum(e.amount), 0) from StaffLedgerEntry e where e.businessId = :businessId")
    BigDecimal totalOwed(@Param("businessId") String businessId);
}
