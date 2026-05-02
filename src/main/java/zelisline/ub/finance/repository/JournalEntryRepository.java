package zelisline.ub.finance.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.finance.domain.JournalEntry;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, String> {
}
