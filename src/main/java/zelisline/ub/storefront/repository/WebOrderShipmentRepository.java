package zelisline.ub.storefront.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import zelisline.ub.storefront.domain.WebOrderShipment;

public interface WebOrderShipmentRepository extends JpaRepository<WebOrderShipment, String> {

    Optional<WebOrderShipment> findByWebOrderId(String webOrderId);

    Optional<WebOrderShipment> findByWebOrderIdAndBusinessId(String webOrderId, String businessId);

    /** Booked shipments still inside the polling window (see {@code PickupMtaaniPoller}). */
    List<WebOrderShipment> findTop200ByBookStatusAndBookedAtAfter(String bookStatus, Instant cutoff);

    /**
     * Row lock for the booking, cancel, and merchant-refresh decision points (scope
     * §13). Holding the lock across the decision is what stops a double-clicked
     * "Book pickup" from creating two parcels: an optimistic version alone cannot,
     * because Hibernate only detects the conflict at flush time — after the create
     * call has already gone out. The unlocked poller relies on {@code @Version}
     * instead, so a poll that races a cancel is rejected rather than overwriting it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from WebOrderShipment s where s.webOrderId = :webOrderId and s.businessId = :businessId")
    Optional<WebOrderShipment> findForUpdate(
            @Param("webOrderId") String webOrderId,
            @Param("businessId") String businessId);
}
