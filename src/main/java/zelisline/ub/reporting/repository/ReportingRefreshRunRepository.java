package zelisline.ub.reporting.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.reporting.domain.ReportingRefreshRun;

public interface ReportingRefreshRunRepository extends JpaRepository<ReportingRefreshRun, String> {

    Optional<ReportingRefreshRun> findFirstByMvNameAndStatusOrderByStartedAtDesc(String mvName, String status);

    Optional<ReportingRefreshRun> findFirstByMvNameOrderByStartedAtDesc(String mvName);
}
