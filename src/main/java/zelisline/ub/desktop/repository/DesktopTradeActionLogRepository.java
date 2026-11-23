package zelisline.ub.desktop.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.desktop.domain.DesktopTradeActionLogEntry;

/** Cloud-side; records each applied till action for idempotent re-push. */
public interface DesktopTradeActionLogRepository extends JpaRepository<DesktopTradeActionLogEntry, String> {
}
