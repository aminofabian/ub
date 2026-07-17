package zelisline.ub.finance.application;

import zelisline.ub.finance.domain.JournalEntry;

public interface LedgerPostingPort {

    /**
     * Persist a balanced journal entry with its lines.
     * Auto-ensures standard accounts exist for the business.
     *
     * @return the persisted journal entry id
     * @throws IllegalStateException if the entry is not balanced
     */
    String post(JournalEntry entry);

    /**
     * Replace all lines for an existing journal entry identified by business + sourceType + sourceId.
     * The existing entry is reused; old lines are deleted and new ones are inserted.
     *
     * @return the reused journal entry id
     * @throws IllegalStateException if the entry is not balanced or not found
     */
    String replace(String businessId, String sourceType, String sourceId, JournalEntry entry);

    /**
     * Delete a journal entry and its lines identified by business + sourceType + sourceId.
     * No-op when no matching entry exists.
     */
    void deleteBySource(String businessId, String sourceType, String sourceId);
}
