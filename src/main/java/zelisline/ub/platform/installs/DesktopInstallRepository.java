package zelisline.ub.platform.installs;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Desktop installs that have checked in with the platform, newest presence first. */
public interface DesktopInstallRepository extends JpaRepository<DesktopInstall, String> {

    /** The console's install list, most recently seen first. */
    List<DesktopInstall> findAllByOrderByLastSeenAtDesc(Pageable pageable);
}
