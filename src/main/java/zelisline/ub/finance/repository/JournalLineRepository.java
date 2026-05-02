package zelisline.ub.finance.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.finance.domain.JournalLine;

public interface JournalLineRepository extends JpaRepository<JournalLine, String> {

    List<JournalLine> findByJournalEntryId(String journalEntryId);
}
