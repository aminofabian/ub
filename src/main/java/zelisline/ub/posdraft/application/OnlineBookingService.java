package zelisline.ub.posdraft.application;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.payroll.application.StaffCommissionPlan;
import zelisline.ub.payroll.application.StaffProfileService;
import zelisline.ub.posdraft.api.dto.OnlineBookingDtos.BookingHoursResponse;
import zelisline.ub.posdraft.api.dto.OnlineBookingDtos.DayHours;
import zelisline.ub.posdraft.api.dto.OnlineBookingDtos.PublicBookingConfirmation;
import zelisline.ub.posdraft.api.dto.OnlineBookingDtos.PublicBookingInfo;
import zelisline.ub.posdraft.api.dto.OnlineBookingDtos.PublicBookingRequest;
import zelisline.ub.posdraft.api.dto.OnlineBookingDtos.PublicBranch;
import zelisline.ub.posdraft.api.dto.OnlineBookingDtos.PublicDay;
import zelisline.ub.posdraft.api.dto.OnlineBookingDtos.PublicDays;
import zelisline.ub.posdraft.api.dto.OnlineBookingDtos.PublicService;
import zelisline.ub.posdraft.api.dto.OnlineBookingDtos.PublicSlots;
import zelisline.ub.posdraft.api.dto.OnlineBookingDtos.PublicStaff;
import zelisline.ub.posdraft.api.dto.OnlineBookingDtos.SetBookingHoursRequest;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.CreateAppointmentRequest;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.TicketLineInput;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.TicketResponse;
import zelisline.ub.posdraft.domain.PosDraft;
import zelisline.ub.posdraft.repository.PosDraftRepository;
import zelisline.ub.serviceops.api.dto.ServiceResponse;
import zelisline.ub.serviceops.application.ServiceCatalogService;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Booking by customers themselves, from the shop's public page.
 *
 * <p>A branch takes online bookings only once someone has set its booking hours. A booking made
 * here is the same thing as one made at the counter — a service ticket that has not arrived yet —
 * with source {@code ONLINE}, so the shop sees and handles both in one place.</p>
 *
 * <p>Nobody is signed in on the public side, so this guards itself: a limit per address, a cap
 * on how many upcoming bookings one phone number may hold, times only on the half hour inside
 * opening hours, and nothing returned that a passer-by should not see (no commission, no phone
 * numbers, no other customers).</p>
 */
@Service
@RequiredArgsConstructor
public class OnlineBookingService {

    private static final List<String> DAY_KEYS = List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");
    private static final int SLOT_MINUTES = 30;
    private static final int MAX_DAYS_AHEAD = 60;
    /** Six weeks: one month as a calendar grid shows it. */
    private static final int MAX_CALENDAR_DAYS = 42;
    /** How soon a customer may book: the shop needs a moment to notice. */
    private static final int LEAD_MINUTES = 30;
    private static final int MAX_UPCOMING_PER_PHONE = 3;
    /** However many addresses and numbers are used, one shop takes this many online bookings a day. */
    private static final int MAX_ONLINE_PER_DAY = 60;
    private static final int MAX_TRACKED_ADDRESSES = 20_000;

    private final BusinessRepository businessRepository;
    private final BranchRepository branchRepository;
    private final PosDraftRepository draftRepository;
    private final ServiceCatalogService serviceCatalogService;
    private final StaffProfileService staffProfileService;
    private final ServiceTicketService serviceTicketService;
    private final ObjectMapper objectMapper;

    @Value("${app.services.online-booking.max-per-minute:6}")
    private int maxPerMinute;

    private final ConcurrentHashMap<String, List<Long>> hits = new ConcurrentHashMap<>();

    // ------------------------------------------------------------ hours (staff)

    @Transactional(readOnly = true)
    public BookingHoursResponse hours(String businessId, String branchId) {
        Branch branch = requireBranch(businessId, branchId);
        Map<String, DayHours> days = parseDays(branch.getBookingHours());
        return new BookingHoursResponse(branch.getId(), !days.isEmpty(), days);
    }

    /** Sets when customers may book online at a branch. No days means online booking is off. */
    @Transactional
    public BookingHoursResponse setHours(String businessId, SetBookingHoursRequest request, String userId) {
        Branch branch = requireBranch(businessId, request.branchId());
        Map<String, DayHours> days = new LinkedHashMap<>();
        if (request.days() != null) {
            for (String key : DAY_KEYS) {
                DayHours hours = request.days().get(key);
                if (hours == null) {
                    continue;
                }
                if (!LocalTime.parse(hours.close()).isAfter(LocalTime.parse(hours.open()))) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Closing time must be after opening time (" + key + ")");
                }
                days.put(key, hours);
            }
            for (String key : request.days().keySet()) {
                if (!DAY_KEYS.contains(key)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown day: " + key);
                }
            }
        }
        if (days.isEmpty()) {
            branch.setBookingHours(null);
        } else {
            ObjectNode root = objectMapper.createObjectNode();
            // Online bookings are recorded as opened by whoever switched online booking on.
            root.put("by", userId);
            ObjectNode node = root.putObject("days");
            for (Map.Entry<String, DayHours> entry : days.entrySet()) {
                node.putArray(entry.getKey()).add(entry.getValue().open()).add(entry.getValue().close());
            }
            branch.setBookingHours(root.toString());
        }
        branchRepository.save(branch);
        return new BookingHoursResponse(branch.getId(), !days.isEmpty(), days);
    }

    // ------------------------------------------------------------------ public

    @Transactional(readOnly = true)
    public PublicBookingInfo info(String slug) {
        Business business = requireBusiness(slug);
        List<PublicBranch> branches = new ArrayList<>();
        for (Branch branch : branchRepository.findByBusinessIdAndDeletedAtIsNullOrderByNameAsc(business.getId())) {
            if (branch.isActive() && !parseDays(branch.getBookingHours()).isEmpty()) {
                branches.add(new PublicBranch(branch.getId(), branch.getName(), branch.getAddress(),
                        parseDays(branch.getBookingHours())));
            }
        }
        if (branches.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        List<PublicService> services = new ArrayList<>();
        for (ServiceResponse service : serviceCatalogService
                .listServices(business.getId(), null, Boolean.TRUE, null, 0, 200).getContent()) {
            if (service.price() == null || service.price().signum() <= 0) {
                // Priced at the counter: it cannot be booked without someone to quote it.
                continue;
            }
            services.add(new PublicService(service.id(), service.name(), service.description(),
                    service.durationMinutes(), service.price(), service.priceIsVariable(), service.staffProfileIds()));
        }
        List<PublicStaff> staff = new ArrayList<>();
        for (StaffCommissionPlan plan : staffProfileService.commissionPlans(business.getId())) {
            if (plan.current() && plan.bookable()) {
                staff.add(new PublicStaff(plan.staffProfileId(), firstName(plan.displayName())));
            }
        }
        return new PublicBookingInfo(business.getName(), zone(business).getId(),
                business.getCurrency() == null ? "KES" : business.getCurrency(), branches, services, staff);
    }

    /** Free start times on a day, for one staff member or, with none named, for anyone who does the service. */
    @Transactional(readOnly = true)
    public PublicSlots slots(
            String slug, String branchId, String serviceId, String staffId, LocalDate date, String clientAddress) {
        if (!allow("slots:" + clientAddress, maxPerMinute * 10)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests; try again in a minute");
        }
        Business business = requireBusiness(slug);
        Branch branch = requireOnlineBranch(business.getId(), branchId);
        ServiceResponse service = requireService(business.getId(), serviceId);
        ZoneId zone = zone(business);
        LocalDate today = LocalDate.now(zone);
        if (date.isBefore(today) || date.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            return new PublicSlots(date, zone.getId(), List.of());
        }
        LocalTime[] hours = hoursOn(branch, date.getDayOfWeek());
        List<String> candidates = candidates(business.getId(), service, staffId);
        if (hours == null || candidates.isEmpty()) {
            return new PublicSlots(date, zone.getId(), List.of());
        }
        Instant dayStart = date.atStartOfDay(zone).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
        List<PosDraft> booked = draftRepository.findBookedBetween(business.getId(), dayStart, dayEnd);
        return new PublicSlots(date, zone.getId(), freeStarts(service, candidates, hours, date, zone, booked));
    }

    /**
     * How many start times are still free on each day of a stretch of the calendar, so the page
     * can show a month at a glance without asking day by day. Days in the past, beyond the booking
     * horizon or on which the branch is closed come back as not open.
     */
    @Transactional(readOnly = true)
    public PublicDays days(
            String slug, String branchId, String serviceId, String staffId, LocalDate from, int count, String clientAddress) {
        if (!allow("slots:" + clientAddress, maxPerMinute * 10)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests; try again in a minute");
        }
        Business business = requireBusiness(slug);
        Branch branch = requireOnlineBranch(business.getId(), branchId);
        ServiceResponse service = requireService(business.getId(), serviceId);
        ZoneId zone = zone(business);
        LocalDate today = LocalDate.now(zone);
        LocalDate last = today.plusDays(MAX_DAYS_AHEAD);
        int span = Math.max(1, Math.min(count, MAX_CALENDAR_DAYS));
        List<String> candidates = candidates(business.getId(), service, staffId);
        // One read for the whole stretch, clipped to the days that can be booked at all.
        LocalDate readFrom = from.isBefore(today) ? today : from;
        LocalDate readTo = from.plusDays(span).isAfter(last.plusDays(1)) ? last.plusDays(1) : from.plusDays(span);
        List<PosDraft> booked = candidates.isEmpty() || !readFrom.isBefore(readTo)
                ? List.of()
                : draftRepository.findBookedBetween(business.getId(),
                        readFrom.atStartOfDay(zone).toInstant(), readTo.atStartOfDay(zone).toInstant());
        List<PublicDay> out = new ArrayList<>();
        for (int i = 0; i < span; i++) {
            LocalDate date = from.plusDays(i);
            LocalTime[] hours = date.isBefore(today) || date.isAfter(last) ? null : hoursOn(branch, date.getDayOfWeek());
            if (hours == null) {
                out.add(new PublicDay(date, false, 0));
            } else {
                int free = candidates.isEmpty() ? 0 : freeStarts(service, candidates, hours, date, zone, booked).size();
                out.add(new PublicDay(date, true, free));
            }
        }
        return new PublicDays(zone.getId(), out);
    }

    private static List<Instant> freeStarts(
            ServiceResponse service, List<String> candidates, LocalTime[] hours, LocalDate date, ZoneId zone,
            List<PosDraft> booked) {
        Instant earliest = Instant.now().plus(LEAD_MINUTES, ChronoUnit.MINUTES);
        List<Instant> starts = new ArrayList<>();
        ZonedDateTime cursor = date.atTime(hours[0]).atZone(zone);
        ZonedDateTime close = date.atTime(hours[1]).atZone(zone);
        while (!cursor.plusMinutes(service.durationMinutes()).isAfter(close)) {
            Instant start = cursor.toInstant();
            Instant end = start.plus(Math.max(service.durationMinutes(), 5), ChronoUnit.MINUTES);
            if (!start.isBefore(earliest) && anyFree(candidates, booked, start, end)) {
                starts.add(start);
            }
            cursor = cursor.plusMinutes(SLOT_MINUTES);
        }
        return starts;
    }

    /**
     * Makes the booking. Not transactional itself: each attempt to book a staff member is its own
     * transaction, so when the first choice has just been taken the next can still be tried.
     */
    public PublicBookingConfirmation book(String slug, PublicBookingRequest request, String clientAddress) {
        if (!allow("book:" + clientAddress, maxPerMinute)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many bookings from here; try again in a minute");
        }
        Business business = requireBusiness(slug);
        Branch branch = requireOnlineBranch(business.getId(), request.branchId());
        ServiceResponse service = requireService(business.getId(), request.serviceId());
        String digits = StkPhoneNormalizer.normalize(request.phone());
        if (digits == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid Kenyan mobile number");
        }
        ZoneId zone = zone(business);
        ZonedDateTime at = request.start().atZone(zone);
        LocalTime[] hours = hoursOn(branch, at.getDayOfWeek());
        boolean onGrid = at.getSecond() == 0 && at.getNano() == 0 && at.getMinute() % SLOT_MINUTES == 0;
        if (hours == null || !onGrid
                || at.toLocalTime().isBefore(hours[0])
                || at.toLocalTime().plusMinutes(service.durationMinutes()).isAfter(hours[1])
                || at.toLocalTime().plusMinutes(service.durationMinutes()).isBefore(at.toLocalTime())
                || request.start().isBefore(Instant.now().plus(LEAD_MINUTES - 1, ChronoUnit.MINUTES))
                || at.toLocalDate().isAfter(LocalDate.now(zone).plusDays(MAX_DAYS_AHEAD))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That time is not available; choose another");
        }
        String openedBy = openedBy(branch);
        if (openedBy == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        // The request id is the client ticket id, scoped so a guessed id cannot collide with a till's.
        String clientId = "web-" + request.requestId();
        boolean replay = draftRepository.findByBusinessIdAndClientDraftId(business.getId(), clientId).isPresent();
        if (!replay && draftRepository.countUpcomingOnlineForPhone(business.getId(), "+" + digits, Instant.now())
                >= MAX_UPCOMING_PER_PHONE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This number already has bookings waiting; call the shop to add another");
        }
        if (!replay && draftRepository.countOnlineCreatedSince(
                business.getId(), Instant.now().minus(1, ChronoUnit.DAYS)) >= MAX_ONLINE_PER_DAY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Online booking is full for today; call the shop to book");
        }
        List<String> candidates = candidates(business.getId(), service, request.staffId());
        if (candidates.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That time is not available; choose another");
        }
        // Letters only: the name is shown to staff and must never carry a link or a message.
        String name = request.name().replaceAll("[^\\p{L} '\\-]", " ").trim().replaceAll("\\s+", " ");
        if (name.length() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter your name");
        }
        for (String staff : candidates) {
            try {
                TicketResponse ticket = serviceTicketService.book(business.getId(), new CreateAppointmentRequest(
                        branch.getId(), clientId, staff, request.start(),
                        // No automatic reminder: the number is unverified, and a text to it would
                        // be paid for by the shop. Staff can tick the reminder once they trust it.
                        List.of(new TicketLineInput(service.id(), null)), null, name, "+" + digits, Boolean.FALSE),
                        openedBy, "ONLINE");
                return new PublicBookingConfirmation("#" + ticket.ticketNumber(), service.name(),
                        firstName(ticket.staffName()), ticket.scheduledStart(), ticket.scheduledEnd(), branch.getName());
            } catch (ResponseStatusException ex) {
                if (ex.getStatusCode().value() != HttpStatus.CONFLICT.value()) {
                    // Nothing about why reaches a passer-by.
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "That time is not available; choose another");
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "That time has just been taken; choose another");
    }

    // ------------------------------------------------------------------ helpers

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
                // Someone is cycling addresses. Forget them all rather than grow without end; the
                // per-shop daily cap still holds.
                hits.clear();
            }
        }
        return allowed[0];
    }

    private static boolean anyFree(List<String> staff, List<PosDraft> booked, Instant start, Instant end) {
        for (String id : staff) {
            boolean busy = false;
            for (PosDraft other : booked) {
                if (id.equals(other.getRequestedStaffProfileId())
                        && other.getScheduledStart().isBefore(end) && other.getScheduledEnd().isAfter(start)) {
                    busy = true;
                    break;
                }
            }
            if (!busy) {
                return true;
            }
        }
        return false;
    }

    /** Staff who could take the booking: the one asked for, or everyone who does the service. */
    private List<String> candidates(String businessId, ServiceResponse service, String staffId) {
        List<String> serving = new ArrayList<>();
        for (StaffCommissionPlan plan : staffProfileService.commissionPlans(businessId)) {
            if (plan.current() && plan.bookable()
                    && (service.staffProfileIds().isEmpty() || service.staffProfileIds().contains(plan.staffProfileId()))) {
                serving.add(plan.staffProfileId());
            }
        }
        if (staffId == null || staffId.isBlank()) {
            return serving;
        }
        return serving.contains(staffId.trim()) ? List.of(staffId.trim()) : List.of();
    }

    private ServiceResponse requireService(String businessId, String serviceId) {
        ServiceResponse service;
        try {
            service = serviceCatalogService.getService(businessId, serviceId.trim());
        } catch (ResponseStatusException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        if (!service.active()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        return service;
    }

    private Business requireBusiness(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        return businessRepository.findBySlugAndDeletedAtIsNull(slug.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
    }

    private Branch requireOnlineBranch(String businessId, String branchId) {
        Branch branch = branchId == null || branchId.isBlank() ? null : branchRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(branchId.trim(), businessId).orElse(null);
        if (branch == null || !branch.isActive() || parseDays(branch.getBookingHours()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        return branch;
    }

    private Branch requireBranch(String businessId, String branchId) {
        if (branchId == null || branchId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a branch");
        }
        return branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId.trim(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
    }

    private LocalTime[] hoursOn(Branch branch, DayOfWeek day) {
        DayHours hours = parseDays(branch.getBookingHours()).get(DAY_KEYS.get(day.getValue() - 1));
        return hours == null ? null : new LocalTime[] { LocalTime.parse(hours.open()), LocalTime.parse(hours.close()) };
    }

    private String openedBy(Branch branch) {
        try {
            JsonNode by = branch.getBookingHours() == null ? null : objectMapper.readTree(branch.getBookingHours()).get("by");
            return by == null || by.isNull() || by.asText().isBlank() ? null : by.asText();
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private Map<String, DayHours> parseDays(String json) {
        Map<String, DayHours> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            JsonNode days = objectMapper.readTree(json).get("days");
            if (days == null) {
                return out;
            }
            for (String key : DAY_KEYS) {
                JsonNode pair = days.get(key);
                if (pair != null && pair.isArray() && pair.size() == 2) {
                    LocalTime open = LocalTime.parse(pair.get(0).asText());
                    LocalTime close = LocalTime.parse(pair.get(1).asText());
                    if (close.isAfter(open)) {
                        out.put(key, new DayHours(pair.get(0).asText(), pair.get(1).asText()));
                    }
                }
            }
        } catch (JsonProcessingException | RuntimeException ex) {
            // Hours that cannot be read mean online booking is off, not an error page.
            return new LinkedHashMap<>();
        }
        return out;
    }

    private static ZoneId zone(Business business) {
        try {
            return ZoneId.of(business.getTimezone() == null ? "Africa/Nairobi" : business.getTimezone());
        } catch (RuntimeException ex) {
            return ZoneId.of("Africa/Nairobi");
        }
    }

    /** Only a first name goes on a public page. */
    private static String firstName(String name) {
        if (name == null || name.isBlank()) {
            return "Staff";
        }
        String first = name.trim().split("\\s+")[0];
        return first.substring(0, 1).toUpperCase(Locale.ROOT) + first.substring(1);
    }
}
