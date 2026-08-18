package zelisline.ub.airtime.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.airtime.domain.AirtimeOrder;

public interface AirtimeOrderRepository extends JpaRepository<AirtimeOrder, String> {

    Optional<AirtimeOrder> findByBusinessIdAndIdempotencyKey(String businessId, String idempotencyKey);

    Optional<AirtimeOrder> findByReference(String reference);

    Optional<AirtimeOrder> findByProviderTransactionId(String providerTransactionId);

    Optional<AirtimeOrder> findByIdAndBusinessId(String id, String businessId);

    List<AirtimeOrder> findByBusinessIdOrderByCreatedAtDesc(String businessId, Pageable pageable);

    List<AirtimeOrder> findByBusinessIdAndChannelOrderByCreatedAtDesc(
            String businessId, String channel, Pageable pageable);

    long countByBusinessIdAndChannelAndStatus(String businessId, String channel, String status);

    long countByBusinessIdAndChannelAndStatusIn(String businessId, String channel, List<String> statuses);

    long countByBusinessIdAndChannelAndStatusAndCompletedAtGreaterThanEqual(
            String businessId, String channel, String status, Instant since);

    List<AirtimeOrder> findByBusinessIdAndStatusInOrderByCreatedAtAsc(String businessId, List<String> statuses);

    List<AirtimeOrder> findByStatusInOrderByCreatedAtAsc(List<String> statuses);

    List<AirtimeOrder> findByWebOrderId(String webOrderId);

    List<AirtimeOrder> findByBusinessIdAndCustomerIdAndStatusOrderByCompletedAtDesc(
            String businessId, String customerId, String status, Pageable pageable);

    @Query("""
            select coalesce(sum(o.amount), 0) from AirtimeOrder o
            where o.businessId = :businessId
              and o.status in ('REQUESTED', 'SUBMITTED', 'PENDING', 'SUCCESS')
              and o.requestedAt >= :since
            """)
    BigDecimal sumCommittedSince(@Param("businessId") String businessId, @Param("since") Instant since);

    @Query("""
            select coalesce(sum(o.commission), 0) from AirtimeOrder o
            where o.businessId = :businessId
              and o.status = 'SUCCESS'
              and o.completedAt >= :since
            """)
    BigDecimal sumCommissionSince(@Param("businessId") String businessId, @Param("since") Instant since);

    @Query("""
            select coalesce(sum(o.amount), 0) from AirtimeOrder o
            where o.businessId = :businessId
              and o.channel = :channel
              and o.status = 'SUCCESS'
            """)
    BigDecimal sumSuccessAmountByChannel(
            @Param("businessId") String businessId, @Param("channel") String channel);

    @Query("""
            select coalesce(sum(o.commission), 0) from AirtimeOrder o
            where o.businessId = :businessId
              and o.channel = :channel
              and o.status = 'SUCCESS'
            """)
    BigDecimal sumSuccessCommissionByChannel(
            @Param("businessId") String businessId, @Param("channel") String channel);

    @Query("""
            select coalesce(sum(o.amount), 0) from AirtimeOrder o
            where o.businessId = :businessId
              and o.channel = :channel
              and o.status = 'SUCCESS'
              and o.completedAt >= :since
            """)
    BigDecimal sumSuccessAmountByChannelSince(
            @Param("businessId") String businessId,
            @Param("channel") String channel,
            @Param("since") Instant since);

    @Query("""
            select coalesce(sum(o.commission), 0) from AirtimeOrder o
            where o.businessId = :businessId
              and o.channel = :channel
              and o.status = 'SUCCESS'
              and o.completedAt >= :since
            """)
    BigDecimal sumSuccessCommissionByChannelSince(
            @Param("businessId") String businessId,
            @Param("channel") String channel,
            @Param("since") Instant since);

    @Query("""
            select coalesce(sum(o.amount), 0) from AirtimeOrder o
            where o.status = 'SUCCESS'
              and o.completedAt >= :since
            """)
    BigDecimal sumPlatformSuccessSince(@Param("since") Instant since);
}
