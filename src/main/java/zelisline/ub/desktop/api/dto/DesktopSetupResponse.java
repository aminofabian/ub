package zelisline.ub.desktop.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result of a successful first-run setup (see {@code DESKTOP_INSTALLATION.md} §9).
 * The frontend uses this to redirect to {@code /login} after the wizard submits.
 *
 * @param businessId   the seeded business UUID (matches {@code app.desktop.business-id})
 * @param businessName the display name entered in the wizard
 * @param userId       the owner user UUID
 * @param ownerEmail   the owner's login email
 * @param ownerRoleId  the owner's role UUID
 * @param mainBranchId the default "Main Branch" created during setup
 * @param shiftId      the starter shift opened on the main branch (may be null
 *                     if the shift-open failed non‑fatally)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DesktopSetupResponse(
    String businessId,
    String businessName,
    String userId,
    String ownerEmail,
    String ownerRoleId,
    String mainBranchId,
    String shiftId
) {}
