package zelisline.ub.storeroom.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.storeroom.domain.StoreRoomMovement;

public interface StoreRoomMovementRepository extends JpaRepository<StoreRoomMovement, String> {

    /**
     * Movements created inside {@code [from, to)}, newest first. The caller supplies
     * the window (the dashboard computes its own local-day boundaries) and a
     * {@link Pageable} to cap the page.
     */
    List<StoreRoomMovement> findByBusinessIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
            String businessId,
            Instant from,
            Instant to,
            Pageable pageable
    );
}
