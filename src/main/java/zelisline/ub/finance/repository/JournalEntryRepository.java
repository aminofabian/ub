package zelisline.ub.finance.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.finance.domain.JournalEntry;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, String> {

    Optional<JournalEntry> findByBusinessIdAndSourceTypeAndSourceId(
            String businessId,
            String sourceType,
            String sourceId
    );

    /** Prefer when source keys may not be unique (partial/double posts). Oldest first. */
    List<JournalEntry> findAllByBusinessIdAndSourceTypeAndSourceIdOrderByCreatedAtAscIdAsc(
            String businessId,
            String sourceType,
            String sourceId
    );
}
