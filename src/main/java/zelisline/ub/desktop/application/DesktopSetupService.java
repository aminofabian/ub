package zelisline.ub.desktop.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.desktop.api.dto.DesktopSetupRequest;
import zelisline.ub.desktop.api.dto.DesktopSetupResponse;
import zelisline.ub.identity.application.IdentityService;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.sales.api.dto.PostOpenShiftRequest;
import zelisline.ub.sales.api.dto.ShiftResponse;
import zelisline.ub.sales.application.ShiftService;
import zelisline.ub.tenancy.application.BusinessOnboardingSettingsService;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * First-run setup for the desktop SKU (see {@code DESKTOP_INSTALLATION.md} §9).
 *
 * <p>This is the desktop analogue of the cloud {@code TenancyService.createBusiness}
 * + {@code AuthRegistrationService.register} pair, fused into one transaction
 * with three additions:
 *
 * <ul>
 *   <li>Creates a default branch ("Main Branch") for the business.</li>
 *   <li>Opens a starter shift on that branch.</li>
 *   <li>Writes {@code ${APP_DATA}/.initialized}, JVM opts, and MariaDB config
 *       via {@link DesktopInitializationService}.</li>
 * </ul>
 *
 * <p>The endpoint is idempotent: the second call returns
 * {@link HttpStatus#CONFLICT}. The frontend's root router can also call
 * {@link #isSetupRequired()} cheaply on every visit (or use the
 * {@link DesktopInitializationService#isInitialized()} shell‑side check).
 */
@Service
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopSetupService {

    private static final Logger log = LoggerFactory.getLogger(
        DesktopSetupService.class
    );

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final BranchRepository branchRepository;
    private final PasswordEncoder passwordEncoder;
    private final BusinessOnboardingSettingsService businessOnboardingSettingsService;
    private final CatalogBootstrapService catalogBootstrapService;
    private final ShiftService shiftService;
    private final DesktopInitializationService initializationService;

    @Value("${app.desktop.business-id:}")
    private String desktopBusinessId;

    public String getDesktopBusinessId() {
        return desktopBusinessId == null ? "" : desktopBusinessId.trim();
    }

    /**
     * Cheap "should we route to /setup?" probe. Returns {@code true} when no
     * {@code Business} row exists for {@code app.desktop.business-id} AND
     * the {@code .initialized} marker file is absent.
     */
    @Transactional(readOnly = true)
    public boolean isSetupRequired() {
        String id = getDesktopBusinessId();
        if (id.isEmpty()) {
            return true;
        }
        if (initializationService.isInitialized()) {
            return false;
        }
        return businessRepository.findByIdAndDeletedAtIsNull(id).isEmpty();
    }

    @Transactional
    public DesktopSetupResponse completeSetup(DesktopSetupRequest request) {
        String id = getDesktopBusinessId();
        if (id.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "app.desktop.business-id is not configured — set APP_DESKTOP_BUSINESS_ID before running setup"
            );
        }

        if (businessRepository.findByIdAndDeletedAtIsNull(id).isPresent()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Desktop install is already set up"
            );
        }

        Role ownerRole = roleRepository
            .findSystemRoleByKey(IdentityService.OWNER_ROLE_KEY)
            .orElseThrow(() ->
                new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Owner role is not configured — Flyway migrations may not have run"
                )
            );

        // ── Business ───────────────────────────────────────────────────

        Business business = new Business();
        business.setId(id);
        business.setName(request.businessName().trim());
        business.setSlug(buildSlug(request));
        business.setCurrency(normalizeCode(request.currency(), "KES"));
        business.setCountryCode(normalizeCode(request.countryCode(), "KE"));
        business.setTimezone(fallback(request.timezone(), "Africa/Nairobi"));
        business.setSubscriptionTier("desktop");
        business.setSettings(buildBusinessSettings(request));
        Business saved = businessRepository.save(business);

        // Merge onboarding-settings seed the same way cloud createBusiness does.
        saved.setSettings(
            businessOnboardingSettingsService.mergeInitialPending(
                saved.getSettings()
            )
        );
        saved = businessRepository.save(saved);
        catalogBootstrapService.seedDefaultItemTypesIfMissing(saved.getId());

        // ── Default branch ─────────────────────────────────────────────

        Branch mainBranch = new Branch();
        mainBranch.setBusinessId(saved.getId());
        mainBranch.setName("Main Branch");
        mainBranch.setAddress(null);
        mainBranch.setActive(true);
        mainBranch = branchRepository.save(mainBranch);
        log.info(
            "[DesktopSetup] created default branch={} for business={}",
            mainBranch.getId(),
            saved.getId()
        );

        // ── Owner user ─────────────────────────────────────────────────

        String email = normaliseEmail(request.ownerEmail());
        userRepository
            .findByBusinessIdAndEmailAndDeletedAtIsNull(saved.getId(), email)
            .ifPresent(u -> {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "An account with this email already exists for this business"
                );
            });

        User owner = new User();
        owner.setBusinessId(saved.getId());
        owner.setEmail(email);
        owner.setName(request.ownerName().trim());
        owner.setPasswordHash(passwordEncoder.encode(request.ownerPassword()));
        owner.setRoleId(ownerRole.getId());
        owner.setStatus(UserStatus.ACTIVE);

        // PIN — store as-is if provided (the identity module's own PIN flow holds the encoder).
        if (request.ownerPin() != null && !request.ownerPin().isBlank()) {
            owner.setPinHash(passwordEncoder.encode(request.ownerPin().trim()));
        }

        User savedOwner = userRepository.save(owner);

        // ── Starter shift ──────────────────────────────────────────────

        String shiftId = null;
        try {
            PostOpenShiftRequest shiftReq = new PostOpenShiftRequest(
                mainBranch.getId(),
                BigDecimal.ZERO, // opening cash — user counts later
                "Initial shift — opened by first-run wizard",
                Collections.emptyList()
            );
            ShiftResponse shift = shiftService.openShift(
                saved.getId(),
                shiftReq,
                savedOwner.getId()
            );
            shiftId = shift.id();
            log.info(
                "[DesktopSetup] opened starter shift={} on branch={}",
                shiftId,
                mainBranch.getId()
            );
        } catch (Exception e) {
            // Non-fatal: the owner can open a shift manually. Log and continue
            // so the wizard doesn't fail on a transient shift-open edge case.
            log.warn(
                "[DesktopSetup] could not open starter shift: {}",
                e.getMessage()
            );
        }

        // ── Filesystem artefacts ───────────────────────────────────────

        try {
            initializationService.completeInitialization(
                saved.getId(),
                request.hardwareTier(),
                Instant.now()
            );
        } catch (IOException e) {
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to write initialization files: " + e.getMessage(),
                e
            );
        }

        // ── License key placeholder ────────────────────────────────────

        String licenseKey = request.licenseKey();
        if (licenseKey != null && !licenseKey.isBlank()) {
            log.info(
                "[DesktopSetup] license key provided ({} chars) — verification lands in step 10 (§10).",
                licenseKey.length()
            );
            // Stored in business.settings under `desktop.license.key`; the
            // license verifier from step 10 will read and validate it on boot.
            saved = businessRepository
                .findByIdAndDeletedAtIsNull(saved.getId())
                .orElseThrow();
            ObjectNode settings;
            try {
                settings = (ObjectNode) JSON.readTree(saved.getSettings());
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to parse business settings JSON", e);
            }
            ObjectNode desktopNode = settings.withObject("/desktop");
            desktopNode.put("licenseKey", licenseKey.trim());
            try {
                saved.setSettings(JSON.writeValueAsString(settings));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize business settings JSON", e);
            }
            businessRepository.save(saved);
        }

        log.info(
            "[DesktopSetup] seeded business={} owner={} role={} branch={} shift={} tier={}",
            saved.getId(),
            savedOwner.getId(),
            ownerRole.getId(),
            mainBranch.getId(),
            shiftId,
            fallback(request.hardwareTier(), "B")
        );

        return new DesktopSetupResponse(
            saved.getId(),
            saved.getName(),
            savedOwner.getId(),
            savedOwner.getEmail(),
            ownerRole.getId(),
            mainBranch.getId(),
            shiftId
        );
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private String buildBusinessSettings(DesktopSetupRequest request) {
        ObjectNode settings = JSON.createObjectNode();
        ObjectNode desktop = settings.putObject("desktop");
        String tier = request.hardwareTier();
        desktop.put(
            "hardwareTier",
            tier != null && !tier.isBlank() ? tier.trim().toUpperCase() : "B"
        );

        if (request.taxRate() != null) {
            desktop.put("defaultTaxRate", request.taxRate());
        }
        if (
            request.receiptHeader() != null &&
            !request.receiptHeader().isBlank()
        ) {
            desktop.put("receiptHeader", request.receiptHeader().trim());
        }
        if (
            request.receiptFooter() != null &&
            !request.receiptFooter().isBlank()
        ) {
            desktop.put("receiptFooter", request.receiptFooter().trim());
        }

        try {
            return JSON.writeValueAsString(settings);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize business settings JSON", e);
        }
    }

    private static String buildSlug(DesktopSetupRequest request) {
        String fromRequest = request.slug();
        if (fromRequest != null && !fromRequest.isBlank()) {
            return normalizeSlug(fromRequest);
        }
        return normalizeSlug(request.businessName());
    }

    private static String normalizeSlug(String value) {
        String stripped = value
            .trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
        return stripped.isEmpty() ? "palmart-desktop" : stripped;
    }

    private static String normalizeCode(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String normaliseEmail(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
