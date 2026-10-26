package zelisline.ub.domainoffer.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.domainoffer.domain.DomainOfferOrder;
import zelisline.ub.domainoffer.domain.DomainOfferStatus;

public interface DomainOfferOrderRepository extends JpaRepository<DomainOfferOrder, String> {

    Optional<DomainOfferOrder> findByPayTokenAndDeletedAtIsNull(String payToken);

    Optional<DomainOfferOrder> findByIdAndDeletedAtIsNull(String id);

    Optional<DomainOfferOrder> findByCampaignIdAndBusinessId(String campaignId, String businessId);

    List<DomainOfferOrder> findByCampaignIdOrderByCreatedAtAsc(String campaignId);

    List<DomainOfferOrder> findByBusinessIdAndDeletedAtIsNullOrderByCreatedAtDesc(String businessId);

    List<DomainOfferOrder> findByCampaignIdAndStatusOrderByCreatedAtAsc(
            String campaignId, DomainOfferStatus status);

    List<DomainOfferOrder> findByStatusOrderByCreatedAtAsc(DomainOfferStatus status);

    Optional<DomainOfferOrder> findFirstByBusinessIdAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(
            String businessId, List<DomainOfferStatus> statuses);

    @Query("""
        select o from DomainOfferOrder o
         where o.deletedAt is null
           and o.campaignId = :campaignId
           and o.status = zelisline.ub.domainoffer.domain.DomainOfferStatus.INVITED
           and o.touchCount < :maxTouches
           and o.nextTouchAt is not null
           and o.nextTouchAt <= :now
         order by o.nextTouchAt asc
        """)
    List<DomainOfferOrder> findDueTouches(
            @Param("campaignId") String campaignId,
            @Param("maxTouches") int maxTouches,
            @Param("now") Instant now,
            Pageable pageable);

    @Query("""
        select count(o) from DomainOfferOrder o
         where o.deletedAt is null
           and o.campaignId = :campaignId
           and o.status = zelisline.ub.domainoffer.domain.DomainOfferStatus.INVITED
           and o.touchCount < :maxTouches
           and o.nextTouchAt is not null
        """)
    long countPendingTouches(@Param("campaignId") String campaignId, @Param("maxTouches") int maxTouches);

    @Query("""
        select count(o) from DomainOfferOrder o
         where o.deletedAt is null
           and o.campaignId = :campaignId
           and o.status = zelisline.ub.domainoffer.domain.DomainOfferStatus.INVITED
           and o.touchCount < :maxTouches
           and o.nextTouchAt is not null
           and o.nextTouchAt <= :now
        """)
    long countDueTouches(
            @Param("campaignId") String campaignId,
            @Param("maxTouches") int maxTouches,
            @Param("now") java.time.Instant now);

    long countByCampaignIdAndDeletedAtIsNull(String campaignId);

    long countByCampaignIdAndStatusAndDeletedAtIsNull(String campaignId, DomainOfferStatus status);

    @Query("""
        select coalesce(sum(o.amountCents), 0) from DomainOfferOrder o
         where o.deletedAt is null
           and o.campaignId = :campaignId
           and o.status in :statuses
        """)
    long sumAmountForStatuses(
            @Param("campaignId") String campaignId,
            @Param("statuses") List<DomainOfferStatus> statuses);

    @Query("""
        select o from DomainOfferOrder o
         where o.deletedAt is null
           and o.campaignId = :campaignId
           and (:status is null or o.status = :status)
           and (
             :q is null
             or lower(coalesce(o.reference, '')) like lower(concat('%', :q, '%'))
             or lower(coalesce(o.requestedDomain, '')) like lower(concat('%', :q, '%'))
             or lower(coalesce(o.suggestedDomain, '')) like lower(concat('%', :q, '%'))
             or lower(coalesce(o.contactEmail, '')) like lower(concat('%', :q, '%'))
           )
         order by
           case when o.status = zelisline.ub.domainoffer.domain.DomainOfferStatus.PAID then 0
                when o.status = zelisline.ub.domainoffer.domain.DomainOfferStatus.IN_PROGRESS then 1
                else 2 end,
           o.createdAt desc
        """)
    List<DomainOfferOrder> searchCampaignOrders(
            @Param("campaignId") String campaignId,
            @Param("status") DomainOfferStatus status,
            @Param("q") String q,
            Pageable pageable);

    @Query("""
        select o from DomainOfferOrder o
         where o.deletedAt is null
           and o.campaignId = :campaignId
           and o.status = zelisline.ub.domainoffer.domain.DomainOfferStatus.INVITED
           and o.touchCount >= :maxTouches
           and o.lastTouchAt <= :cutoff
        """)
    List<DomainOfferOrder> findExhausted(
            @Param("campaignId") String campaignId,
            @Param("maxTouches") int maxTouches,
            @Param("cutoff") Instant cutoff);

    @Query("""
        select count(o) from DomainOfferOrder o
         where o.deletedAt is null
           and o.campaignId = :campaignId
           and o.status in (zelisline.ub.domainoffer.domain.DomainOfferStatus.PAID,
                            zelisline.ub.domainoffer.domain.DomainOfferStatus.IN_PROGRESS)
           and o.slaDueAt is not null
           and o.slaDueAt < :now
        """)
    long countOverdue(@Param("campaignId") String campaignId, @Param("now") Instant now);

    @Query("""
        select o from DomainOfferOrder o
         where o.deletedAt is null
           and o.businessId = :businessId
           and o.status in :statuses
           and o.createdAt >= :since
        """)
    List<DomainOfferOrder> findRecentByBusiness(
            @Param("businessId") String businessId,
            @Param("statuses") List<DomainOfferStatus> statuses,
            @Param("since") Instant since);
}
