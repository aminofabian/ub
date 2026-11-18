package zelisline.ub.storefront.api;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.ClientIpResolver;
import zelisline.ub.storefront.api.dto.PublicRoomDtos.PublicRoomAvailability;
import zelisline.ub.storefront.api.dto.PublicRoomDtos.PublicRoomBookingConfirmation;
import zelisline.ub.storefront.api.dto.PublicRoomDtos.PublicRoomBookingRequest;
import zelisline.ub.storefront.api.dto.PublicRoomDtos.PublicRoomDepositRequest;
import zelisline.ub.storefront.api.dto.PublicRoomDtos.PublicRoomDepositResponse;
import zelisline.ub.storefront.api.dto.PublicRoomDtos.PublicRoomInfo;
import zelisline.ub.storefront.application.OnlineRoomBookingService;

/**
 * The public room-booking storefront (ADR 0031): a guest at {@code {slug}.kiosk.ke} sees a house's
 * rooms and requests a stay, with no account and nothing paid online. Public — no {@code @PreAuthorize};
 * the business is resolved from the {@code {slug}} path segment, never from the body. Rate-limited by
 * client address in the service, and gated by the per-business {@code rooms_online} switch.
 */
@Validated
@RestController
@RequestMapping("/api/v1/public/businesses/{slug}/rooms")
@RequiredArgsConstructor
public class PublicRoomBookingController {

    private final OnlineRoomBookingService service;

    /** The house's lodging page: name, branches, check-in/out times, whether it takes bookings. */
    @GetMapping
    public ResponseEntity<PublicRoomInfo> info(@PathVariable String slug) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.info(slug));
    }

    /** The rooms free for a date range at a branch. */
    @GetMapping("/availability")
    public ResponseEntity<PublicRoomAvailability> availability(
            @PathVariable String slug,
            @RequestParam String branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut,
            HttpServletRequest request) {
        PublicRoomAvailability result =
                service.availability(slug, branchId, checkIn, checkOut, ClientIpResolver.resolve(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    /** Request a stay; it opens a booking on the front desk for the house to confirm. */
    @PostMapping("/bookings")
    public ResponseEntity<PublicRoomBookingConfirmation> book(
            @PathVariable String slug, @Valid @RequestBody PublicRoomBookingRequest body, HttpServletRequest request) {
        PublicRoomBookingConfirmation confirmation =
                service.book(slug, body, ClientIpResolver.resolve(request));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(confirmation);
    }

    /** Pay the booking deposit online by M-Pesa STK; the prompt goes to the guest's phone (ADR 0031). */
    @PostMapping("/deposit")
    public ResponseEntity<PublicRoomDepositResponse> deposit(
            @PathVariable String slug, @Valid @RequestBody PublicRoomDepositRequest body, HttpServletRequest request) {
        PublicRoomDepositResponse result =
                service.initiateDeposit(slug, body, ClientIpResolver.resolve(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}
