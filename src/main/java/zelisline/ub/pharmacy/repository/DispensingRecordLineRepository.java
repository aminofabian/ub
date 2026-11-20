package zelisline.ub.pharmacy.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.pharmacy.domain.DispensingRecordLine;

public interface DispensingRecordLineRepository extends JpaRepository<DispensingRecordLine, String> {

    List<DispensingRecordLine> findByBusinessIdAndRecordIdInOrderByCreatedAtAscIdAsc(
            String businessId, Collection<String> recordIds);

    java.util.Optional<DispensingRecordLine> findByIdAndBusinessId(String id, String businessId);

    List<DispensingRecordLine> findByBusinessIdAndSettlesLineIdIn(String businessId, Collection<String> lineIds);

    /** Lines that supplied less than was prescribed (candidates for an outstanding balance). */
    @org.springframework.data.jpa.repository.Query("""
            select l from DispensingRecordLine l
             where l.businessId = :businessId
               and l.settlesLineId is null
               and l.quantityPrescribed is not null
               and l.quantityPrescribed > l.quantity
               and l.createdAt >= :since
             order by l.createdAt desc, l.id desc
            """)
    List<DispensingRecordLine> findPartialSupplies(
            @org.springframework.data.repository.query.Param("businessId") String businessId,
            @org.springframework.data.repository.query.Param("since") java.time.Instant since,
            org.springframework.data.domain.Pageable pageable);

    List<DispensingRecordLine> findByBusinessIdAndRefillDueOnBetweenOrderByRefillDueOnAscIdAsc(
            String businessId, java.time.LocalDate from, java.time.LocalDate to,
            org.springframework.data.domain.Pageable pageable);
}
