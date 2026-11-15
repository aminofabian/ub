package zelisline.ub.restaurant.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.restaurant.domain.KitchenTicket;

public interface KitchenTicketRepository extends JpaRepository<KitchenTicket, String> {

    Optional<KitchenTicket> findByIdAndBusinessId(String id, String businessId);

    /** Locked, so two cooks marking plates on one ticket work its status out one after the other. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from KitchenTicket t where t.id = :id and t.businessId = :businessId")
    Optional<KitchenTicket> findByIdAndBusinessIdForUpdate(
            @Param("id") String id, @Param("businessId") String businessId);

    List<KitchenTicket> findByBusinessIdAndSaleId(String businessId, String saleId);

    List<KitchenTicket> findByBusinessIdAndOrderId(String businessId, String orderId);

    /** What a kitchen screen shows: tickets still being made, and voids nobody has acknowledged. */
    @Query("""
            select t from KitchenTicket t
             where t.businessId = :businessId
               and t.branchId = :branchId
               and (t.status = 'OPEN' or (t.ticketType = 'VOID' and t.acknowledgedAt is null))
             order by t.sentAt asc
            """)
    List<KitchenTicket> findBoard(
            @Param("businessId") String businessId, @Param("branchId") String branchId,
            org.springframework.data.domain.Pageable page);

    /** Tickets of orders that are made but not yet handed over. */
    @Query("""
            select t from KitchenTicket t
             where t.businessId = :businessId
               and t.branchId = :branchId
               and t.ticketType = 'NEW'
               and t.status in ('OPEN', 'READY')
             order by t.sentAt asc
            """)
    List<KitchenTicket> findNotHandedOver(
            @Param("businessId") String businessId, @Param("branchId") String branchId,
            org.springframework.data.domain.Pageable page);

    @Query("""
            select t from KitchenTicket t
             where t.businessId = :businessId
               and t.branchId = :branchId
               and t.ticketType = 'NEW'
               and t.readyAt is not null
               and t.sentAt >= :from and t.sentAt < :to
            """)
    List<KitchenTicket> findReadyBetween(
            @Param("businessId") String businessId, @Param("branchId") String branchId,
            @Param("from") java.time.Instant from, @Param("to") java.time.Instant to);
}
