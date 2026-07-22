package zelisline.ub.finance.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.finance.domain.JournalEntry;
import zelisline.ub.finance.domain.JournalLine;
import zelisline.ub.finance.repository.JournalEntryRepository;
import zelisline.ub.finance.repository.JournalLineRepository;

@Service
@RequiredArgsConstructor
public class LedgerPostingService implements LedgerPostingPort {

    private final LedgerBootstrapService ledgerBootstrapService;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalLineRepository journalLineRepository;

    @Override
    @Transactional
    public String post(JournalEntry entry) {
        ledgerBootstrapService.ensureStandardAccounts(entry.getBusinessId());
        entry.assertBalanced();
        journalEntryRepository.save(entry);
        for (JournalLine line : entry.getLines()) {
            line.setJournalEntryId(entry.getId());
        }
        journalLineRepository.saveAll(entry.getLines());
        return entry.getId();
    }

    @Override
    @Transactional
    public String replace(String businessId, String sourceType, String sourceId, JournalEntry entry) {
        ledgerBootstrapService.ensureStandardAccounts(businessId);
        entry.assertBalanced();
        List<JournalEntry> matches = journalEntryRepository
                .findAllByBusinessIdAndSourceTypeAndSourceIdOrderByCreatedAtAscIdAsc(
                        businessId, sourceType, sourceId);
        if (matches.isEmpty()) {
            throw new IllegalStateException(
                    "Journal entry not found for replacement: " + sourceType + "/" + sourceId);
        }
        // Keep the oldest; drop duplicate journals left by partial/double posts.
        JournalEntry existing = matches.getFirst();
        for (int i = 1; i < matches.size(); i++) {
            deleteJournalEntry(matches.get(i));
        }
        List<JournalLine> oldLines = journalLineRepository.findByJournalEntryId(existing.getId());
        if (!oldLines.isEmpty()) {
            journalLineRepository.deleteAll(oldLines);
        }
        existing.setEntryDate(entry.getEntryDate());
        existing.setMemo(entry.getMemo());
        journalEntryRepository.save(existing);
        for (JournalLine line : entry.getLines()) {
            line.setJournalEntryId(existing.getId());
        }
        journalLineRepository.saveAll(entry.getLines());
        return existing.getId();
    }

    @Override
    @Transactional
    public void deleteBySource(String businessId, String sourceType, String sourceId) {
        List<JournalEntry> matches = journalEntryRepository
                .findAllByBusinessIdAndSourceTypeAndSourceIdOrderByCreatedAtAscIdAsc(
                        businessId, sourceType, sourceId);
        for (JournalEntry existing : matches) {
            deleteJournalEntry(existing);
        }
    }

    private void deleteJournalEntry(JournalEntry existing) {
        List<JournalLine> oldLines = journalLineRepository.findByJournalEntryId(existing.getId());
        if (!oldLines.isEmpty()) {
            journalLineRepository.deleteAll(oldLines);
        }
        journalEntryRepository.delete(existing);
    }
}
