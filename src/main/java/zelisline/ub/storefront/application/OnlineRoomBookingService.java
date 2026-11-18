package zelisline.ub.storefront.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.messaging.application.RoomBookingConfirmationEvent;
import zelisline.ub.payments.application.DarajaAccountReferences;
import zelisline.ub.payments.application.GatewayStkPushService;
import zelisline.ub.payments.application.PaymentGatewayStkService;
import zelisline.ub.payments.application.StkAudience;
import zelisline.ub.payments.application.StkPushRetryHelper;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.StkPushContextType;
import zelisline.ub.posdraft.PosDraftConstants;
import zelisline.ub.posdraft.application.OrderTicketService;
import zelisline.ub.restaurant.application.DineInService;
import zelisline.ub.restaurant.application.RestaurantSettingsService;
import zelisline.ub.restaurant.api.dto.OrderDtos.OpenOrderRequest;
import zelisline.ub.serviceops.application.ServiceResourceService;
import zelisline.ub.storefront.api.dto.PublicRoomDtos.PublicRoom;
import zelisline.ub.storefront.api.dto.PublicRoomDtos.PublicRoomAvailability;
import zelisline.ub.storefront.api.dto.PublicRoomDtos.PublicRoomBookingConfirmation;
import zelisline.ub.storefront.api.dto.PublicRoomDtos.PublicRoomBookingRequest;
import zelisline.ub.storefront.api.dto.PublicRoomDtos.PublicRoomBranch;
import zelisline.ub.storefront.api.dto.PublicRoomDtos.PublicRoomDepositRequest;
import zelisline.ub.storefront.api.dto.PublicRoomDtos.PublicRoomDepositResponse;
import zelisline.ub.storefront.api.dto.PublicRoomDtos.PublicRoomInfo;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * The public, unauthenticated room-booking storefront (ADR 0031). A guest at {@code {slug}.kiosk.ke}
 * sees the rooms a house lets and what is free, and requests a stay with a name and a phone — no
 * account, nothing paid online; the request opens a BOOKED stay on the front desk to confirm and
 * check in. Modelled on the service-booking storefront (ADR 0020 online bookings):
 *
 * <ul>
 *   <li>The business is resolved from the <b>slug in the path</b>, never from the body or a header,
 *       so one public query can never read another tenant's rooms.</li>
 *   <li>A per-business {@code rooms_online} switch gates the whole surface; off → a generic 404.</li>
 *   <li>A sliding-window limiter per client address slows a flood; the real bound is the
 *       per-phone and per-day caps. The {@code requestId} makes a retry book nobody twice.</li>
 *   <li>Nothing exposed leaks a guest, a phone or money owed; every error body is generic.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class OnlineRoomBookingService {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final int MAX_NIGHTS = 30;
    private static final int MAX_DAYS_AHEAD = 365;
    private static final int MAX_TRACKED_ADDRESSES = 20_000;
    private static final String ONLINE_ACTOR = "online";

    private final BusinessRepository businessRepository;
    private final BranchRepository branchRepository;
    private final RestaurantSettingsService settingsService;
    private final ServiceResourceService resourceService;
    private final OrderTicketService orderTicketService;
    private final DineInService dineInService;
    private final ApplicationEventPublisher eventPublisher;
    private final StkPushRetryHelper stkPushRetryHelper;
    private final GatewayStkPushService gatewayStkPushService;

    @Value("${app.rooms.online-booking.max-per-minute:6}")
    private int maxPerMinute;

    private final ConcurrentHashMap<String, List<Long>> hits = new ConcurrentHashMap<>();

    /** The house's public lodging page: its name, branches, check-in/out times, and whether it books. */
    @Transactional(readOnly = true)
    public PublicRoomInfo info(String slug) {
        Business business = requireLetting(slug);
        List<PublicRoomBranch> branches = new ArrayList<>();
        for (Branch branch : branchRepository.findByBusinessIdAndDeletedAtIsNullOrderByNameAsc(business.getId())) {
            if (branch.isActive() && !resourceService.rooms(business.getId(), branch.getId()).isEmpty()) {
                branches.add(new PublicRoomBranch(branch.getId(), branch.getName()));
            }
        }
        zelisline.ub.restaurant.api.dto.RestaurantDtos.SettingsResponse settings =
                settingsService.read(business.getId());
        return new PublicRoomInfo(business.getName(), !branches.isEmpty(),
                settings.checkInTime() == null ? "14:00" : settings.checkInTime(),
                settings.checkOutTime() == null ? "11:00" : settings.checkOutTime(), branches);
    }

    /** The rooms free for the requested nights at a branch. A room with an overlapping stay is left out. */
    @Transactional(readOnly = true)
    public PublicRoomAvailability availability(String slug, String branchId, LocalDate checkIn, LocalDate checkOut,
            String address) {
        if (!allow("rooms:" + address, maxPerMinute * 10)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Try again in a moment");
        }
        Business business = requireLetting(slug);
        Branch branch = requireBranch(business.getId(), branchId);
        long nights = requireDates(checkIn, checkOut);

        Instant from = checkIn.atTime(14, 0).atZone(NAIROBI).toInstant();
        Instant to = checkOut.atTime(11, 0).atZone(NAIROBI).toInstant();
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (OrderTicketService.Order stay : orderTicketService.roomStaysOverlapping(business.getId(), branch.getId(), from, to)) {
            if (stay.tableResourceId() != null) {
                taken.add(stay.tableResourceId());
            }
        }
        List<PublicRoom> rooms = new ArrayList<>();
        for (ServiceResourceService.RoomRecord room : resourceService.rooms(business.getId(), branch.getId())) {
            if (room.readyToLet() && !taken.contains(room.id())) {
                rooms.add(new PublicRoom(room.id(), room.name(), room.effectiveNightlyRate(),
                        room.effectiveWeekendRate(), null, null, null, room.area()));
            }
        }
        return new PublicRoomAvailability(branch.getId(), checkIn.toString(), checkOut.toString(), (int) nights, rooms);
    }

    /** A guest requests a stay: it opens a BOOKED stay on the front desk for staff to confirm. */
    @Transactional
    public PublicRoomBookingConfirmation book(String slug, PublicRoomBookingRequest request, String address) {
        if (!allow("book:" + address, maxPerMinute)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Try again in a moment");
        }
        Business business = requireLetting(slug);
        Branch branch = requireBranch(business.getId(), request.branchId());
        LocalDate checkIn = parseDate(request.checkIn());
        LocalDate checkOut = parseDate(request.checkOut());
        long nights = requireDates(checkIn, checkOut);

        String name = sanitizeName(request.name());
        String phone = sanitizePhone(request.phone());
        LocalTime inTime = resolveTime(settingsService.read(business.getId()).checkInTime(), LocalTime.of(14, 0));
        LocalTime outTime = resolveTime(settingsService.read(business.getId()).checkOutTime(), LocalTime.of(11, 0));

        // The open-order machinery validates the room and refuses one that is taken or out of use.
        // clientOrderId carries the guest's requestId, so a retry opens no second stay.
        OpenOrderRequest open = new OpenOrderRequest(branch.getId(), OrderTicketService.CHANNEL_LODGING,
                request.roomId(), request.guests() == null ? 1 : request.guests(), null, null, name, phone, null,
                "web-" + request.requestId(),
                checkIn.atTime(inTime).atZone(NAIROBI).toInstant(),
                checkOut.atTime(outTime).atZone(NAIROBI).toInstant(), null);
        var response = dineInService.open(business.getId(), branch.getId(), ONLINE_ACTOR, open, "ONLINE");

        String roomName = response.tableName() != null ? response.tableName() : "your room";
        BigDecimal estimate = response.total() != null && response.total().signum() > 0
                ? response.total()
                : estimateFromRoom(business.getId(), branch.getId(), request.roomId(), nights, checkIn);

        // Acknowledge the guest off-thread after this booking commits (ADR 0031). A send failure or a
        // slow provider must not block or roll back the booking, so this only fires AFTER_COMMIT.
        eventPublisher.publishEvent(new RoomBookingConfirmationEvent(business.getId(), response.id(), name,
                phone, roomName, checkIn.toString(), checkOut.toString(), estimate));

        return new PublicRoomBookingConfirmation(response.id(), roomName, checkIn.toString(), checkOut.toString(),
                (int) nights, estimate, "The house has your request. They will confirm with you by phone.");
    }

    /**
     * A guest asks to pay their booking deposit online by M-Pesa STK (ADR 0031). The prompt is for the
     * stay's current room total; when they pay, the gateway callback records a held deposit on the stay
     * (see {@code GatewayStkPushService}). The business is resolved from the slug and must own the stay,
     * which must be a BOOKED lodging request with no deposit yet. Not {@code @Transactional}: it may
     * block on the gateway for seconds (same as the public web-order STK path); the push row is written
     * in {@code registerPush}'s own transaction.
     */
    public PublicRoomDepositResponse initiateDeposit(String slug, PublicRoomDepositRequest request, String address) {
        if (!allow("deposit:" + address, maxPerMinute)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Try again in a moment");
        }
        Business business = requireLetting(slug);
        OrderTicketService.Order stay = orderTicketService.find(business.getId(), request.bookingId())
                .filter(OrderTicketService.Order::isOpen)
                .filter(o -> OrderTicketService.CHANNEL_LODGING.equals(o.channel()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        if (PosDraftConstants.DEPOSIT_HELD.equals(stay.depositState())) {
            // Already paid — do not prompt again; the desk sees the held deposit.
            return new PublicRoomDepositResponse(false, stay.depositAmount(), null, "A deposit is already paid");
        }
        String phone = request.phone() != null && !request.phone().isBlank()
                ? request.phone().trim()
                : stay.customerPhone();
        if (phone == null || phone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A phone number is needed for M-Pesa");
        }

        BigDecimal amount = dineInService.get(business.getId(), stay.id()).total();
        if (amount == null || amount.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nothing to pay yet");
        }

        PaymentGatewayStkService.StkPushOutcome outcome = stkPushRetryHelper.initiateAfterClearingPhone(
                business.getId(), null, phone, amount,
                DarajaAccountReferences.forRoomDeposit(stay.id()), "Room deposit", StkAudience.STOREFRONT);
        if (!outcome.accepted()) {
            return new PublicRoomDepositResponse(false, amount, null,
                    outcome.message() != null ? outcome.message() : "Could not send M-Pesa prompt");
        }
        GatewayType gatewayType = GatewayType.valueOf(outcome.gatewayType());
        String merchantRef = GatewayType.DARAJA.equals(gatewayType)
                ? DarajaAccountReferences.forRoomDeposit(stay.id())
                : stay.id();
        gatewayStkPushService.registerPush(business.getId(), gatewayType, outcome.configId(),
                outcome.checkoutRequestId(), merchantRef, StkPushContextType.ROOM_BOOKING_DEPOSIT, stay.id(),
                amount, phone);
        return new PublicRoomDepositResponse(true, amount, outcome.checkoutRequestId(), outcome.message());
    }

    // ------------------------------------------------------------------ helpers

    /** The business for a slug, with rooms published online; a generic 404 otherwise (ADR 0031). */
    private Business requireLetting(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        Business business = businessRepository.findBySlugAndDeletedAtIsNull(slug.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        if (!settingsService.read(business.getId()).roomsOnline()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        return business;
    }

    private Branch requireBranch(String businessId, String branchId) {
        Branch branch = branchId == null || branchId.isBlank() ? null
                : branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId.trim(), businessId).orElse(null);
        if (branch == null || !branch.isActive()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        return branch;
    }

    private long requireDates(LocalDate checkIn, LocalDate checkOut) {
        LocalDate today = LocalDate.now(NAIROBI);
        if (checkIn == null || checkOut == null || !checkOut.isAfter(checkIn) || checkIn.isBefore(today)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose your dates");
        }
        long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
        if (nights > MAX_NIGHTS || ChronoUnit.DAYS.between(today, checkIn) > MAX_DAYS_AHEAD) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose your dates");
        }
        return nights;
    }

    private BigDecimal estimateFromRoom(String businessId, String branchId, String roomId, long nights, LocalDate in) {
        for (ServiceResourceService.RoomRecord room : resourceService.rooms(businessId, branchId)) {
            if (room.id().equals(roomId)) {
                return room.effectiveNightlyRate().multiply(BigDecimal.valueOf(nights)).setScale(2, RoundingMode.HALF_UP);
            }
        }
        return BigDecimal.ZERO;
    }

    private static LocalTime resolveTime(String hhmm, LocalTime dflt) {
        return hhmm == null || hhmm.isBlank() ? dflt : LocalTime.parse(hhmm);
    }

    private static LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(raw);
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose your dates");
        }
    }

    /** Letters, spaces, apostrophe and hyphen only, so a name can carry no link or message. */
    private static String sanitizeName(String raw) {
        String cleaned = raw == null ? "" : raw.replaceAll("[^\\p{L} '\\-]", "").trim();
        if (cleaned.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give your name");
        }
        return cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
    }

    private static String sanitizePhone(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("[^0-9+]", "");
        if (digits.replaceAll("[^0-9]", "").length() < 9) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give a phone number");
        }
        return digits.length() > 32 ? digits.substring(0, 32) : digits;
    }

    /** A sliding one-minute window per address. Per-instance, a flood slow-down; not a hard cluster cap. */
    private boolean allow(String address, int perMinute) {
        if (address == null || address.isBlank()) {
            return true;
        }
        long now = System.currentTimeMillis();
        boolean[] allowed = { true };
        hits.compute(address, (key, list) -> {
            List<Long> stamps = list == null ? new ArrayList<>() : new ArrayList<>(list);
            stamps.removeIf(stamp -> stamp < now - 60_000);
            if (stamps.size() >= Math.max(1, perMinute)) {
                allowed[0] = false;
            } else {
                stamps.add(now);
            }
            return stamps;
        });
        if (hits.size() > MAX_TRACKED_ADDRESSES) {
            hits.entrySet().removeIf(entry -> entry.getValue().stream().allMatch(stamp -> stamp < now - 60_000));
            if (hits.size() > MAX_TRACKED_ADDRESSES) {
                hits.clear();
            }
        }
        return allowed[0];
    }

    /** Unused but kept to mark online drafts distinctly if a staff/system id is later wanted. */
    @SuppressWarnings("unused")
    private static String onlineActorId() {
        return ONLINE_ACTOR + "-" + UUID.randomUUID();
    }
}
