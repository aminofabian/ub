package zelisline.ub.platform.logs;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DesktopLogUploadRepository extends JpaRepository<DesktopLogUpload, String> {

    List<DesktopLogUpload> findTop50ByOrderByUploadedAtDesc();

    List<DesktopLogUpload> findTop50ByInstallIdOrderByUploadedAtDesc(String installId);
}
