package zelisline.ub.finance.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.finance.domain.JournalEntry;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, String> {

    Optional<JournalEntry> findByBusinessIdAndSourceTypeAndSourceId(
            String businessId,
            String sourceType,
            String sourceId
    );
}
