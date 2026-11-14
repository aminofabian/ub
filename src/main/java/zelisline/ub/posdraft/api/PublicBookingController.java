package zelisline.ub.posdraft.api;

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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import zelisline.ub.posdraft.api.dto.OnlineBookingDtos.PublicBookingConfirmation;
import zelisline.ub.posdraft.api.dto.OnlineBookingDtos.PublicBookingInfo;
import zelisline.ub.posdraft.api.dto.OnlineBookingDtos.PublicBookingRequest;
import zelisline.ub.posdraft.api.dto.OnlineBookingDtos.PublicDays;
import zelisline.ub.posdraft.api.dto.OnlineBookingDtos.PublicSlots;
import zelisline.ub.posdraft.application.OnlineBookingService;

/**
 * The shop's public booking page. Nobody is signed in: the business comes from the slug in the
 * path, never from a header, and {@link OnlineBookingService} limits and checks every write.
 */
@Validated
@RestController
@RequestMapping("/api/v1/public/businesses/{slug}/booking")
@RequiredArgsConstructor
public class PublicBookingController {

    private final OnlineBookingService onlineBookingService;

    @GetMapping
    public PublicBookingInfo info(@PathVariable String slug) {
        return onlineBookingService.info(slug);
    }

    @GetMapping("/slots")
    public ResponseEntity<PublicSlots> slots(
            @PathVariable String slug,
            @RequestParam String branchId,
            @RequestParam String serviceId,
            @RequestParam(required = false) String staffId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(onlineBookingService.slots(slug, branchId, serviceId, staffId, date, address(request)));
    }

    /** A stretch of the calendar (at most six weeks) with the free start times counted per day. */
    @GetMapping("/days")
    public ResponseEntity<PublicDays> days(
            @PathVariable String slug,
            @RequestParam String branchId,
            @RequestParam String serviceId,
            @RequestParam(required = false) String staffId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(defaultValue = "42") @Min(1) @Max(42) int days,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(onlineBookingService.days(slug, branchId, serviceId, staffId, from, days, address(request)));
    }

    @PostMapping("/appointments")
    public ResponseEntity<PublicBookingConfirmation> book(
            @PathVariable String slug, @Valid @RequestBody PublicBookingRequest body, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(onlineBookingService.book(slug, body, address(request)));
    }

    /**
     * The caller's address as the other public rate limits read it. The header can be forged, so
     * this limit only slows a casual flood; the caps per phone number and per shop per day in
     * {@link OnlineBookingService} are what bound the damage.
     */
    private static String address(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String address = forwarded != null && !forwarded.isBlank()
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
        return address != null && address.length() > 64 ? address.substring(0, 64) : address;
    }
}
