package zelisline.ub.desktop.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * First-run wizard payload (see {@code DESKTOP_INSTALLATION.md} §9). The desktop
 * SKU seeds exactly one business per install, so this is a one-shot endpoint —
 * the second call returns 409 from {@code DesktopSetupService}.
 *
 * <p>All optional fields fall back to the same defaults the cloud
 * {@code TenancyService.createBusiness} uses ({@code KES}, {@code KE},
 * {@code Africa/Nairobi}), so a minimal payload of business name + owner email
 * + password is enough to boot.
 *
 * @param businessName   required, max 191 chars
 * @param slug           optional URL slug; defaults to a slug derived from business name
 * @param currency       3‑char ISO code, default {@code KES}
 * @param countryCode    2‑char ISO code, default {@code KE}
 * @param timezone       IANA zone ID, default {@code Africa/Nairobi}
 * @param ownerName      required, max 191 chars
 * @param ownerEmail     required, valid email
 * @param ownerPassword  required, 8–100 chars
 * @param ownerPin       4‑digit cashier PIN (optional — owner can set it later)
 * @param taxRate        default VAT / tax rate as a percentage, 0–100, default 16
 * @param receiptHeader  printed at the top of every receipt (max 255 chars)
 * @param receiptFooter  printed at the bottom of every receipt (max 255 chars)
 * @param hardwareTier   sizing tier from §2: {@code A} (single till, light),
 *                       {@code B} (single till, full), {@code C} (LAN server)
 * @param licenseKey     optional offline license key; omit for 30‑day trial
 */
public record DesktopSetupRequest(
    @NotBlank @Size(max = 191) String businessName,
    @Size(max = 191) String slug,
    @Size(min = 3, max = 3) String currency,
    @Size(min = 2, max = 2) String countryCode,
    @Size(max = 100) String timezone,
    @NotBlank @Size(max = 191) String ownerName,
    @NotBlank @Email @Size(max = 191) String ownerEmail,
    @NotBlank @Size(min = 8, max = 100) String ownerPassword,
    @Pattern(regexp = "[0-9]{4}") String ownerPin,
    @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal taxRate,
    @Size(max = 255) String receiptHeader,
    @Size(max = 255) String receiptFooter,
    @Pattern(regexp = "A|B|C") String hardwareTier,
    @Size(max = 2048) String licenseKey
) {}
