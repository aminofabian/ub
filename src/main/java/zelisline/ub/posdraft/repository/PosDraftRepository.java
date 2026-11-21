package zelisline.ub.posdraft.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.posdraft.domain.PosDraft;

public interface PosDraftRepository extends JpaRepository<PosDraft, String> {

    Optional<PosDraft> findByIdAndBusinessId(String id, String businessId);

    Optional<PosDraft> findByBusinessIdAndClientDraftId(String businessId, String clientDraftId);

    /** Clinic (ADR 0034): a patient's paid visits, newest first — for finding the visit a review follows. */
    List<PosDraft> findTop20ByBusinessIdAndPatientIdAndStatusOrderByCompletedAtDesc(
            String businessId, String patientId, String status);

    /** Clinic: visits paid at a branch since a moment (today's "finished" count). */
    long countByBusinessIdAndBranchIdAndStatusAndPatientIdIsNotNullAndCompletedAtGreaterThanEqual(
            String businessId, String branchId, String status, java.time.Instant since);

    /** The restaurant order (or other ticket of that kind) a sale settled, if any. */
    Optional<PosDraft> findFirstByBusinessIdAndSaleIdAndDraftKind(String businessId, String saleId, String draftKind);

    Optional<PosDraft> findByIdAndBusinessIdAndDraftKind(String id, String businessId, String draftKind);

    /** Locked, so two devices changing one order take turns. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from PosDraft d where d.id = :id and d.businessId = :businessId and d.draftKind = :draftKind")
    Optional<PosDraft> findByIdAndBusinessIdAndDraftKindForUpdate(
            @Param("id") String id, @Param("businessId") String businessId, @Param("draftKind") String draftKind);

    /** Tickets of one kind and status at a branch, oldest first: the open orders of a restaurant floor. */
    List<PosDraft> findByBusinessIdAndBranchIdAndDraftKindAndStatusOrderByCreatedAtAsc(
            String businessId, String branchId, String draftKind, String status);

    /** Orders booked for a time window at a branch (reservations), soonest first. */
    @Query("""
            select d from PosDraft d
             where d.businessId = :businessId
               and d.branchId = :branchId
               and d.draftKind = 'ORDER'
               and d.status = 'pending'
               and d.serviceStage = 'BOOKED'
               and d.scheduledStart < :to
               and d.scheduledEnd > :from
             order by d.scheduledStart asc
            """)
    List<PosDraft> findOrderBookingsOverlapping(
            @Param("businessId") String businessId, @Param("branchId") String branchId,
            @Param("from") java.time.Instant from, @Param("to") java.time.Instant to);

    /**
     * Lodging stays holding a room over a window at a branch, whether still booked or already
     * checked in. Unlike a table — where a walk-in is deliberately not blocked — a room cannot hold
     * two guests at once, so a seated stay ({@code service_stage} cleared at check-in) counts too
     * (ADR 0030). A settled stay no longer holds the room.
     */
    @Query("""
            select d from PosDraft d
             where d.businessId = :businessId
               and d.branchId = :branchId
               and d.draftKind = 'ORDER'
               and d.status = 'pending'
               and d.orderChannel = 'LODGING'
               and (d.serviceStage = 'BOOKED' or d.serviceStage is null)
               and d.scheduledStart < :to
               and d.scheduledEnd > :from
             order by d.scheduledStart asc
            """)
    List<PosDraft> findRoomStaysOverlapping(
            @Param("businessId") String businessId, @Param("branchId") String branchId,
            @Param("from") java.time.Instant from, @Param("to") java.time.Instant to);

    /** A customer's lodging stays, newest first — their stay history (ADR 0030), capped by the caller. */
    @Query("""
            select d from PosDraft d
             where d.businessId = :businessId
               and d.draftKind = 'ORDER'
               and d.orderChannel = 'LODGING'
               and d.customerId = :customerId
             order by coalesce(d.scheduledStart, d.createdAt) desc
            """)
    List<PosDraft> findLodgingStaysForCustomer(
            @Param("businessId") String businessId, @Param("customerId") String customerId,
            org.springframework.data.domain.Pageable page);

    /**
     * Delivery orders at a branch that have been paid or otherwise closed but not yet handed to
     * the customer, newest closing first. Bounded by when they closed, so one nobody ever marked
     * as delivered does not stay on the floor for good.
     */
    @Query("""
            select d from PosDraft d
             where d.businessId = :businessId
               and d.branchId = :branchId
               and d.draftKind = 'ORDER'
               and d.status = 'completed'
               and d.orderChannel = 'DELIVERY'
               and d.deliveredAt is null
               and d.completedAt > :since
             order by d.completedAt desc
            """)
    List<PosDraft> findSettledDeliveriesNotDelivered(
            @Param("businessId") String businessId, @Param("branchId") String branchId,
            @Param("since") java.time.Instant since);

    List<PosDraft> findByBusinessIdAndIdInAndDraftKind(
            String businessId, java.util.Collection<String> ids, String draftKind);

    @Query("""
            select d from PosDraft d
             where d.businessId = :businessId
               and d.branchId = :branchId
               and d.status = :status
               and d.draftKind = 'CART'
               and d.updatedAt >= :since
             order by d.updatedAt desc
            """)
    List<PosDraft> findRecentByBranchAndStatus(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("status") String status,
            @Param("since") Instant since
    );

    @Query("""
            select d from PosDraft d
             where d.businessId = :businessId
               and d.branchId = :branchId
               and d.status = :status
               and d.createdBy = :createdBy
               and d.draftKind = 'CART'
               and d.updatedAt >= :since
             order by d.updatedAt desc
            """)
    List<PosDraft> findRecentByBranchStatusAndCreator(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("status") String status,
            @Param("createdBy") String createdBy,
            @Param("since") Instant since
    );

    /**
     * Open service tickets on the floor at a branch (the queue): priority first, then oldest.
     * Appointments that have not arrived yet are not on the floor.
     */
    @Query("""
            select d from PosDraft d
             where d.businessId = :businessId
               and d.branchId = :branchId
               and d.status = 'pending'
               and d.draftKind = 'SERVICE'
               and d.serviceStage <> 'BOOKED'
             order by d.queuePriority desc, d.createdAt asc
            """)
    List<PosDraft> findOpenServiceTickets(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId
    );



    /**
     * Open tickets being served in a given chair — used to stop two customers sharing one.
     * A locking read on purpose: under InnoDB's default isolation a plain read would use the
     * transaction's earlier snapshot and miss a ticket another till has just put in the chair.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select d from PosDraft d
             where d.businessId = :businessId
               and d.branchId = :branchId
               and d.status = 'pending'
               and d.draftKind = 'SERVICE'
               and d.serviceStage = 'IN_SERVICE'
               and d.stationNo = :stationNo
            """)
    List<PosDraft> findInServiceAtStation(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("stationNo") int stationNo
    );

    /** A staff member's bookings that touch a window, for working out free times. Not a locking read. */
    @Query("""
            select d from PosDraft d
             where d.businessId = :businessId
               and d.draftKind = 'SERVICE'
               and d.status = 'pending'
               and d.serviceStage = 'BOOKED'
               and d.scheduledStart < :end
               and d.scheduledEnd > :start
            """)
    List<PosDraft> findBookedBetween(
            @Param("businessId") String businessId, @Param("start") Instant start, @Param("end") Instant end);

    /** Upcoming online bookings made with one phone number: the cap that stops one person filling the book. */
    @Query("""
            select count(d) from PosDraft d
             where d.businessId = :businessId
               and d.draftKind = 'SERVICE'
               and d.status = 'pending'
               and d.serviceStage = 'BOOKED'
               and d.bookingSource = 'ONLINE'
               and d.bookingPhone = :phone
               and d.scheduledStart > :now
            """)
    long countUpcomingOnlineForPhone(
            @Param("businessId") String businessId, @Param("phone") String phone, @Param("now") Instant now);

    /** Online bookings a business has received since a moment, whatever became of them. */
    @Query("""
            select count(d) from PosDraft d
             where d.businessId = :businessId
               and d.draftKind = 'SERVICE'
               and d.bookingSource = 'ONLINE'
               and d.createdAt > :since
            """)
    long countOnlineCreatedSince(@Param("businessId") String businessId, @Param("since") Instant since);

    /**
     * Bookings holding a resource that overlap a time window. A locking read, taken under the
     * resource's row lock, so two people cannot book the same bed for the same time.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select d from PosDraft d
             where d.businessId = :businessId
               and d.draftKind = 'SERVICE'
               and d.status = 'pending'
               and d.serviceStage = 'BOOKED'
               and d.scheduledStart < :end
               and d.scheduledEnd > :start
               and exists (select 1 from PosDraftLine l
                            where l.draftId = d.id and l.deleted = false and l.resourceId = :resourceId)
            """)
    List<PosDraft> findBookedOverlappingResource(
            @Param("businessId") String businessId,
            @Param("resourceId") String resourceId,
            @Param("start") Instant start,
            @Param("end") Instant end);

    /** Bookings, in any business, that are due a reminder text. */
    @Query("""
            select d from PosDraft d
             where d.draftKind = 'SERVICE'
               and d.status = 'pending'
               and d.serviceStage = 'BOOKED'
               and d.sendReminder = true
               and d.reminderSentAt is null
               and d.bookingPhone is not null
               and d.scheduledStart > :now
               and d.scheduledStart <= :until
               and d.createdAt < :bookedBefore
             order by d.scheduledStart asc
            """)
    List<PosDraft> findDueReminders(
            @Param("now") Instant now,
            @Param("until") Instant until,
            @Param("bookedBefore") Instant bookedBefore,
            org.springframework.data.domain.Pageable page
    );

    /** Claims a reminder so that only one sender ever texts the customer. @return 1 when claimed */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update versioned PosDraft d
               set d.reminderSentAt = :now
             where d.id = :id
               and d.reminderSentAt is null
               and d.status = 'pending'
               and d.serviceStage = 'BOOKED'
            """)
    int claimReminder(@Param("id") String id, @Param("now") Instant now);

    /** Appointments at a branch that start within a window, earliest first (the day calendar). */
    @Query("""
            select d from PosDraft d
             where d.businessId = :businessId
               and d.branchId = :branchId
               and d.draftKind = 'SERVICE'
               and d.scheduledStart is not null
               and d.scheduledStart >= :from
               and d.scheduledStart < :to
             order by d.scheduledStart asc
            """)
    List<PosDraft> findAppointmentsBetween(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("from") Instant from,
            @Param("to") Instant to
    );

    /**
     * A staff member's booked appointments that overlap a time window. A locking read, taken
     * under that staff member's row lock, so two people booking the same slot cannot both pass.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select d from PosDraft d
             where d.businessId = :businessId
               and d.draftKind = 'SERVICE'
               and d.status = 'pending'
               and d.serviceStage = 'BOOKED'
               and d.requestedStaffProfileId = :staffProfileId
               and d.scheduledStart < :end
               and d.scheduledEnd > :start
            """)
    List<PosDraft> findBookedOverlapping(
            @Param("businessId") String businessId,
            @Param("staffProfileId") String staffProfileId,
            @Param("start") Instant start,
            @Param("end") Instant end
    );

    List<PosDraft> findByBusinessIdAndShiftIdAndStatus(
            String businessId,
            String shiftId,
            String status
    );

    /**
     * Untagged pending drafts at a branch updated during a shift window.
     * When {@code createdBy} is null (shared branch shift), all creators match.
     */
    @Query("""
            select d from PosDraft d
             where d.businessId = :businessId
               and d.branchId = :branchId
               and d.status = :status
               and d.shiftId is null
               and d.updatedAt >= :since
               and (:createdBy is null or d.createdBy = :createdBy)
             order by d.updatedAt desc
            """)
    List<PosDraft> findUntaggedPendingForShiftClose(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("status") String status,
            @Param("since") Instant since,
            @Param("createdBy") String createdBy
    );
}
