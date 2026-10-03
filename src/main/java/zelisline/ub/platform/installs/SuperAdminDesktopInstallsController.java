package zelisline.ub.platform.installs;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.desktop.license.DesktopLicenseIssue;
import zelisline.ub.desktop.license.DesktopLicenseIssueRepository;

/**
 * Super Admin → Platform → Desktop licenses → Installs: the registry of Kiosk
 * Desktop tills that have checked in, with a one-click activation key.
 *
 * <p>Secured by {@code PERM_sa.console.full} via {@code /api/v1/super-admin/**}
 * in {@code SecurityConfig}. Issuing reuses
 * {@link zelisline.ub.desktop.license.DesktopLicenseIssuanceService} so the token
 * is signed and emailed exactly like the manual issue form.
 */
@RestController
@RequestMapping("/api/v1/super-admin/desktop-installs")
@RequiredArgsConstructor
public class SuperAdminDesktopInstallsController {

    private static final int MAX_LIST_LIMIT = 200;

    private final DesktopInstallRepository installRepository;
    private final DesktopInstallService installService;
    private final DesktopLicenseIssueRepository issueRepository;

    public record InstallRow(
            String installId,
            String machineId,
            String businessName,
            String contactEmail,
            String cloudBusinessId,
            String appVersion,
            String platform,
            String licenseState,
            String plan,
            Instant firstSeenAt,
            Instant lastSeenAt,
            String lastIp,
            String lastLicenseIssueId,
            Instant autoIssuedAt) {}

    public record IssueForInstallRequest(
            /** Blank = the install owner/cloud business owner email. */
            String email,
            /** Blank = 365 days; ignored when {@code perpetual} is true. */
            Integer days,
            Boolean perpetual) {}

    public record IssueForInstallResponse(
            String id,
            String token,
            String businessName,
            String plan,
            Instant issuedAt,
            Instant expiresAt,
            String machineFingerprint,
            String emailedTo,
            boolean emailSent) {}

    /** One previously issued token for an install (no token — use resend to re-email it). */
    public record InstallIssueRow(
            String id,
            String plan,
            Instant issuedAt,
            Instant expiresAt,
            String recipientEmail,
            boolean emailSent,
            Instant createdAt) {}

    /** Installs that have checked in, most recently seen first. */
    @GetMapping
    public List<InstallRow> list(@RequestParam(defaultValue = "50") int limit) {
        int capped = Math.min(Math.max(limit, 1), MAX_LIST_LIMIT);
        return installRepository
            .findAllByOrderByLastSeenAtDesc(PageRequest.of(0, capped))
            .stream()
            .map(SuperAdminDesktopInstallsController::toRow)
            .toList();
    }

    /**
     * License history for an install, newest first. Tokens are matched by the
     * machine's fingerprint, so a re-install of the same machine still shows the
     * keys it was issued (the token itself is not returned — use the desktop
     * license resend endpoint to re-email it).
     */
    @GetMapping("/{installId}/issues")
    public List<InstallIssueRow> issues(
            @PathVariable String installId,
            @RequestParam(defaultValue = "50") int limit) {
        DesktopInstall install = installRepository
            .findById(installId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Install not found"));
        if (install.getMachineFingerprint() == null || install.getMachineFingerprint().isBlank()) {
            return List.of();
        }
        int capped = Math.min(Math.max(limit, 1), MAX_LIST_LIMIT);
        return issueRepository
            .findAllByMachineFingerprintOrderByCreatedAtDesc(
                install.getMachineFingerprint(), PageRequest.of(0, capped))
            .stream()
            .map(SuperAdminDesktopInstallsController::toIssueRow)
            .toList();
    }

    /**
     * Issue a machine-bound activation key for an install and email it. The
     * Machine ID and shop name come from the install registry, so the operator
     * never has to paste them.
     */
    @PostMapping("/{installId}/issue-and-email")
    public IssueForInstallResponse issueAndEmail(
            @PathVariable String installId,
            @RequestBody(required = false) IssueForInstallRequest body) {
        DesktopInstall install = installRepository
            .findById(installId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Install not found"));
        String email = body == null ? null : body.email();
        Integer days = body == null ? null : body.days();
        Boolean perpetual = body == null ? null : body.perpetual();
        DesktopLicenseIssue issue = installService.issueAndEmail(install, email, days, perpetual);
        return new IssueForInstallResponse(
            issue.getId(),
            issue.getToken(),
            issue.getBusinessName(),
            issue.getPlan(),
            issue.getIssuedAt(),
            issue.getExpiresAt(),
            issue.getMachineFingerprint(),
            issue.getRecipientEmail(),
            issue.isEmailSent());
    }

    private static InstallRow toRow(DesktopInstall row) {
        return new InstallRow(
            row.getInstallId(),
            row.getMachineFingerprint(),
            row.getBusinessName(),
            row.getContactEmail(),
            row.getCloudBusinessId(),
            row.getAppVersion(),
            row.getPlatform(),
            row.getLicenseState(),
            row.getPlan(),
            row.getFirstSeenAt(),
            row.getLastSeenAt(),
            row.getLastIp(),
            row.getLastLicenseIssueId(),
            row.getAutoIssuedAt());
    }

    private static InstallIssueRow toIssueRow(DesktopLicenseIssue row) {
        return new InstallIssueRow(
            row.getId(),
            row.getPlan(),
            row.getIssuedAt(),
            row.getExpiresAt(),
            row.getRecipientEmail(),
            row.isEmailSent(),
            row.getCreatedAt());
    }
}
