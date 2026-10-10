package zelisline.ub.crm.application;

import java.util.List;

/**
 * Seam for resolving who may work the WhatsApp inbox (hand-off agents).
 *
 * <p>Implemented outside {@code crm} — in {@code messaging} — because {@code identity} already
 * depends on {@code messaging} ({@code identity → messaging → crm}), so {@code crm} must not
 * depend on {@code identity} directly. Keeping the lookup behind this port preserves the acyclic
 * boundary in {@code docs/adr/0011-whatsapp-crm-boundary.md}.
 */
public interface InboxAgentDirectory {

    /** A candidate hand-off agent: an active user able to read the inbox. */
    record Agent(String id, String name) {
    }

    /** Active users of {@code businessId} able to work the inbox, ordered by name (empty if none). */
    List<Agent> listInboxAgents(String businessId);
}
