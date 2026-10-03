package zelisline.ub.desktop.license;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** History of license tokens issued from the Super Admin console. */
public interface DesktopLicenseIssueRepository extends JpaRepository<DesktopLicenseIssue, String> {

    /** Newest issues first (the console's "Recent licenses" list). */
    List<DesktopLicenseIssue> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Every token issued for a machine, newest first (per-install history). */
    List<DesktopLicenseIssue> findAllByMachineFingerprintOrderByCreatedAtDesc(
            String machineFingerprint,
            Pageable pageable);

    /** Tokens for a machine that are still valid (perpetual, or not yet expired). */
    @Query("""
        select count(i) from DesktopLicenseIssue i
         where i.machineFingerprint = :fingerprint
           and (i.expiresAt is null or i.expiresAt > :now)
        """)
    long countActiveByMachineFingerprint(
            @Param("fingerprint") String fingerprint,
            @Param("now") Instant now);
}
