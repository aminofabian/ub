package zelisline.ub.desktop.license;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;

/**
 * Super Admin → Platform → Desktop licenses: issue Ed25519-signed Kiosk Desktop
 * license tokens and optionally email them to the shop owner.
 *
 * <p>Secured by {@code ROLE_SUPER_ADMIN} via {@code /api/v1/super-admin/**} in
 * {@code SecurityConfig}. The signing key is resolved by {@link DesktopLicenseIssuer}:
 * the deployment env var ({@code APP_DESKTOP_LICENSE_PRIVATE_KEY}) first, then the
 * console-managed key in {@code desktop_license_issuer_config} — so it can be
 * configured from this console without a redeploy. Without any key the issuer
 * reports unconfigured and {@code /issue} returns 503.
 */
@RestController
@RequestMapping("/api/v1/super-admin/desktop-licenses")
@RequiredArgsConstructor
public class SuperAdminDesktopLicensesController {

    private static final int MAX_LIST_LIMIT = 200;

    private final DesktopLicenseIssuanceService issuanceService;
    private final DesktopLicenseIssueRepository issueRepository;
    private final DesktopLicenseIssuerConfigService issuerConfigService;

    /** Issuer configuration + public-key sync hint for the console UI. */
    @GetMapping("/status")
    public DesktopLicenseIssuerConfigService.IssuerStatus status() {
        return issuerConfigService.status();
    }

    /** Store a signing key pasted from {@code generate-license.sh keys}. */
    @PostMapping("/issuer/key")
    public DesktopLicenseIssuerConfigService.IssuerStatus setIssuerKey(
            @Valid @RequestBody DesktopLicenseIssuerConfigService.SetIssuerKeyRequest body) {
        return issuerConfigService.setKey(body);
    }

    /** Generate a fresh signing key pair in the console (returns the public key). */
    @PostMapping("/issuer/generate")
    public DesktopLicenseIssuerConfigService.GenerateKeyResult generateIssuerKey() {
        return issuerConfigService.generate();
    }

    /** Remove the console-managed signing key (env var still wins if set). */
    @DeleteMapping("/issuer/key")
    public DesktopLicenseIssuerConfigService.IssuerStatus clearIssuerKey() {
        return issuerConfigService.clear();
    }

    /** Recent issued licenses, newest first. */
    @GetMapping
    public List<IssueRecord> list(
            @RequestParam(defaultValue = "50") int limit) {
        int capped = Math.min(Math.max(limit, 1), MAX_LIST_LIMIT);
        return issueRepository
            .findAllByOrderByCreatedAtDesc(PageRequest.of(0, capped))
            .stream()
            .map(SuperAdminDesktopLicensesController::toRecord)
            .toList();
    }

    /** Re-email the stored token of a previously issued license. */
    @PostMapping("/{id}/resend")
    public IssueRecord resend(@PathVariable String id) {
        DesktopLicenseIssue row = issueRepository
            .findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "License issue not found"));
        if (row.getRecipientEmail() == null || row.getRecipientEmail().isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "This license was never emailed — issue a new one with an email address."
            );
        }
        issuanceService.sendLicenseEmail(row.getRecipientEmail(), row.getToken(), row.getBusinessName(), row.getPlan(), row.getExpiresAt());
        row.setEmailSent(true);
        DesktopLicenseIssue saved = issueRepository.save(row);
        return toRecord(saved);
    }

    /** Sign a token; the console shows it with a copy button. */
    @PostMapping("/issue")
    public IssueResponse issue(@Valid @RequestBody IssueRequest request) {
        return doIssue(request, null);
    }

    /** Sign a token and email it to the shop owner. */
    @PostMapping("/issue-and-email")
    public IssueResponse issueAndEmail(@Valid @RequestBody IssueRequest request) {
        if (request.email() == null || request.email().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An email address is required");
        }
        return doIssue(request, request.email().trim());
    }

    private IssueResponse doIssue(IssueRequest request, String emailTo) {
        DesktopLicenseIssue saved = issuanceService.issue(
            request.businessName(),
            request.plan(),
            request.days(),
            request.expiresAt(),
            request.perpetual(),
            request.fingerprint(),
            emailTo
        );
        return toResponse(saved, saved.getToken());
    }

    private static IssueResponse toResponse(DesktopLicenseIssue row, String token) {
        return new IssueResponse(
            row.getId(),
            token,
            row.getBusinessName(),
            row.getPlan(),
            row.getIssuedAt(),
            row.getExpiresAt(),
            row.getMachineFingerprint(),
            row.getRecipientEmail(),
            row.isEmailSent(),
            row.getCreatedAt()
        );
    }

    private static IssueRecord toRecord(DesktopLicenseIssue row) {
        return new IssueRecord(
            row.getId(),
            row.getBusinessName(),
            row.getPlan(),
            row.getIssuedAt(),
            row.getExpiresAt(),
            row.getMachineFingerprint(),
            row.getRecipientEmail(),
            row.isEmailSent(),
            row.getCreatedAt()
        );
    }

    public record IssueRequest(
            @NotBlank String businessName,
            @Pattern(regexp = "(?:free|starter|business|growth|enterprise)?",
                     message = "plan must be a subscription tier: free, starter, business, growth, or enterprise") String plan,
            Integer days,
            String expiresAt,
            Boolean perpetual,
            /** The till's Machine ID (Settings → License) — required so the key only works on that machine. */
            @NotBlank(message = "Machine ID is required — get it from the till (Settings → License)") String fingerprint,
            @Email(message = "email must be a valid address") String email
    ) {}

    public record IssueResponse(
            String id,
            String token,
            String businessName,
            String plan,
            Instant issuedAt,
            Instant expiresAt,
            String machineFingerprint,
            String emailedTo,
            boolean emailSent,
            Instant createdAt
    ) {}

    /** List/row projection (no token — the console shows tokens only right after issuing). */
    public record IssueRecord(
            String id,
            String businessName,
            String plan,
            Instant issuedAt,
            Instant expiresAt,
            String machineFingerprint,
            String recipientEmail,
            boolean emailSent,
            Instant createdAt
    ) {}
}
