package zelisline.ub.desktop.repository;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.desktop.domain.DesktopTradeAction;

@Profile("desktop")
public interface DesktopTradeActionRepository extends JpaRepository<DesktopTradeAction, String> {

    /** Actions not yet confirmed by the cloud, oldest first. */
    List<DesktopTradeAction> findByPushedAtIsNullOrderByMadeAtAsc(Pageable pageable);

    /** Actions the cloud refused, newest first, so the till can show the reason. */
    List<DesktopTradeAction> findByOutcomeOrderByMadeAtDesc(String outcome, Pageable pageable);
}
