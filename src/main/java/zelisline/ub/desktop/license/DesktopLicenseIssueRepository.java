package zelisline.ub.desktop.license;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** History of license tokens issued from the Super Admin console. */
public interface DesktopLicenseIssueRepository extends JpaRepository<DesktopLicenseIssue, String> {

    /** Newest issues first (the console's "Recent licenses" list). */
    List<DesktopLicenseIssue> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
