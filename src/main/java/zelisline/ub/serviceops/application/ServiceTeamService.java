package zelisline.ub.serviceops.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.api.dto.CreateUserRequest;
import zelisline.ub.identity.api.dto.UserResponse;
import zelisline.ub.identity.application.IdentityService;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.payroll.api.dto.CreateStaffProfileRequest;
import zelisline.ub.payroll.api.dto.StaffProfileResponse;
import zelisline.ub.payroll.application.StaffCommissionPlan;
import zelisline.ub.payroll.application.StaffProfileService;
import zelisline.ub.payroll.domain.StaffProfile;
import zelisline.ub.payroll.repository.StaffProfileRepository;
import zelisline.ub.identity.application.RequestPermissionService;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.serviceops.api.dto.CommissionDtos.StatementRow;
import zelisline.ub.serviceops.api.dto.TeamDtos.AddTeamMemberRequest;
import zelisline.ub.serviceops.api.dto.TeamDtos.GiveLoginRequest;
import zelisline.ub.serviceops.api.dto.TeamDtos.MyBooking;
import zelisline.ub.serviceops.api.dto.TeamDtos.MyEarningsResponse;
import zelisline.ub.serviceops.api.dto.TeamDtos.TeamMemberResponse;

/**
 * The people who serve customers, as people who can sign in. A team member is a staff profile;
 * giving them a login creates an ordinary user with the {@code service_staff} role and links the
 * two, so everything they have earned stays on the same profile.
 */
@Service
@RequiredArgsConstructor
public class ServiceTeamService {

    public static final String SERVICE_STAFF_ROLE_KEY = "service_staff";
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    @PersistenceContext
    private EntityManager entityManager;

    private final IdentityService identityService;
    private final RoleRepository roleRepository;
    private final StaffProfileService staffProfileService;
    private final StaffProfileRepository staffProfileRepository;
    private final StaffEarningsService staffEarningsService;
    private final RequestPermissionService permissionService;
    private final zelisline.ub.identity.repository.UserRepository userRepository;

    /** Adds a team member, with a login when an email and PIN are given. */
    @Transactional
    public TeamMemberResponse add(String businessId, AddTeamMemberRequest request, TenantPrincipal caller) {
        String name = request.displayName().trim();
        boolean hasEmail = request.email() != null && !request.email().isBlank();
        boolean hasPin = request.pin() != null && !request.pin().isBlank();
        if (hasEmail != hasPin) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "To sign in they need both an email and a PIN; leave both empty for no login");
        }
        // Everyone on the team is an account, so they are in Users, Payroll and every report. Without
        // an email and PIN the account simply cannot sign in yet.
        UserResponse user = hasEmail
                ? createLogin(businessId, name, request.email(), request.pin(), request.phone(), request.branchId(), caller)
                : createAccountWithoutSignIn(businessId, name, request.phone(), request.branchId(), caller);
        StaffProfile profile = staffProfileService.ensureProfile(businessId, user.id());
        profile.setBookable(true);
        if (request.phone() != null && !request.phone().isBlank()) {
            profile.setPhone(request.phone().trim());
        }
        staffProfileRepository.save(profile);
        return new TeamMemberResponse(profile.getId(), name, user.id(), hasEmail ? user.email() : null, hasEmail);
    }

    /**
     * Gives an account to every current team member who was added by name only, before everyone
     * was made an account. Safe to run again: someone who already has one is left alone. A
     * person who cannot be given one (the plan's user limit is reached) is reported, not lost.
     */
    @Transactional
    public zelisline.ub.serviceops.api.dto.TeamDtos.SyncAccountsResponse syncAccounts(
            String businessId, TenantPrincipal caller) {
        int created = 0;
        java.util.List<String> skipped = new java.util.ArrayList<>();
        for (StaffProfile row : staffProfileService.findProfilesByBusiness(businessId)) {
            if (row.getUserId() != null
                    || zelisline.ub.payroll.domain.EmploymentStatus.TERMINATED.equals(row.getEmploymentStatus())) {
                continue;
            }
            // Locked and read again, so two screens opening at once do not both make the account.
            staffProfileService.lockForLedger(businessId, row.getId());
            StaffProfile profile = staffProfileRepository.findByIdAndBusinessId(row.getId(), businessId).orElse(null);
            if (profile == null || profile.getUserId() != null) {
                continue;
            }
            String name = profile.getDisplayName() == null || profile.getDisplayName().isBlank()
                    ? "Staff" : profile.getDisplayName().trim();
            try {
                UserResponse user = createAccountWithoutSignIn(businessId, name, profile.getPhone(), null, caller);
                profile.setUserId(user.id());
                profile.setLoginlessNameKey(null);
                profile.setIncludeInPayroll(true);
                staffProfileRepository.save(profile);
                created++;
            } catch (ResponseStatusException refused) {
                skipped.add(name);
            }
        }
        return new zelisline.ub.serviceops.api.dto.TeamDtos.SyncAccountsResponse(created, skipped);
    }

    /** User ids of team members who have a way to sign in (a real address, not the placeholder). */
    @Transactional(readOnly = true)
    public java.util.Set<String> userIdsWhoCanSignIn(String businessId) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (zelisline.ub.identity.domain.User user : userRepository.findByBusinessIdAndDeletedAtIsNull(businessId)) {
            if (!IdentityService.isNoSignInEmail(user.getEmail())) {
                out.add(user.getId());
            }
        }
        return out;
    }

    /** Turns a name-only team member into one who can sign in. */
    @Transactional
    public TeamMemberResponse giveLogin(
            String businessId, String staffProfileId, GiveLoginRequest request, TenantPrincipal caller) {
        // Locked, so two managers cannot give the same person two logins.
        staffProfileService.lockForLedger(businessId, staffProfileId);
        StaffProfile profile = staffProfileRepository.findByIdAndBusinessId(staffProfileId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff member not found"));
        String name = profile.getDisplayName() == null || profile.getDisplayName().isBlank()
                ? "Staff" : profile.getDisplayName().trim();
        if (profile.getUserId() != null) {
            // They already have an account that cannot sign in: give that one its address and PIN.
            if (!permissionService.hasPermission(caller.roleId(), "users.create")) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not allowed to add people who can sign in");
            }
            UserResponse existing = identityService.giveSignIn(businessId, profile.getUserId(), request.email(), request.pin());
            return new TeamMemberResponse(profile.getId(), name, existing.id(), existing.email(), true);
        }
        UserResponse user = createLogin(businessId, name, request.email(), request.pin(), profile.getPhone(),
                request.branchId(), caller);
        profile.setUserId(user.id());
        profile.setLoginlessNameKey(null);
        staffProfileRepository.save(profile);
        return new TeamMemberResponse(profile.getId(), name, user.id(), user.email(), true);
    }

    /** The signed-in person's own earnings and day. 404 when they are not on the team. */
    @Transactional(readOnly = true)
    public MyEarningsResponse mine(String businessId, String userId) {
        StaffCommissionPlan plan = staffProfileService.commissionPlanForUser(businessId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "You are not set up as someone who serves customers"));
        String profileId = plan.staffProfileId();
        LocalDate today = LocalDate.now(NAIROBI);
        StatementRow row = staffEarningsService.statement(businessId, today, today).rows().stream()
                .filter(r -> profileId.equals(r.staffProfileId()))
                .findFirst()
                .orElse(null);

        Instant now = Instant.now();
        Instant endOfDay = today.plusDays(1).atStartOfDay(NAIROBI).toInstant();
        List<Object[]> booked = entityManager.createQuery("""
                select d.id, d.scheduledStart, d.scheduledEnd, d.bookingName
                  from PosDraft d
                 where d.businessId = :businessId
                   and d.draftKind = 'SERVICE'
                   and d.status = 'pending'
                   and d.serviceStage = 'BOOKED'
                   and d.requestedStaffProfileId = :staff
                   and d.scheduledEnd > :now
                   and d.scheduledStart < :end
                 order by d.scheduledStart asc
                """, Object[].class)
                .setParameter("businessId", businessId)
                .setParameter("staff", profileId)
                .setParameter("now", now)
                .setParameter("end", endOfDay)
                .setMaxResults(50)
                .getResultList();
        List<MyBooking> bookings = new ArrayList<>(booked.size());
        for (Object[] b : booked) {
            List<String> names = entityManager.createQuery(
                            "select l.itemName from PosDraftLine l where l.draftId = :id and l.deleted = false "
                                    + "order by l.lineIndex asc", String.class)
                    .setParameter("id", b[0])
                    .getResultList();
            bookings.add(new MyBooking((String) b[0], (Instant) b[1], (Instant) b[2], firstName((String) b[3]),
                    String.join(", ", names)));
        }
        var entries = new ArrayList<>(staffEarningsService.entries(businessId, profileId, today.minusDays(30), today));
        entries.sort(Comparator.comparing(
                zelisline.ub.serviceops.api.dto.CommissionDtos.LedgerEntryResponse::occurredAt).reversed());
        return new MyEarningsResponse(
                profileId,
                plan.displayName(),
                plan.type(),
                plan.rate(),
                staffEarningsService.balance(businessId, profileId),
                row == null ? 0 : row.servicesDone(),
                row == null ? ZERO : row.commission().add(row.reversals()),
                row == null ? ZERO : row.tips(),
                row == null ? ZERO : row.paid(),
                entries.size() > 60 ? entries.subList(0, 60) : entries,
                bookings);
    }

    private UserResponse createAccountWithoutSignIn(
            String businessId, String name, String phone, String branchId, TenantPrincipal caller) {
        if (!permissionService.hasPermission(caller.roleId(), "users.create")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not allowed to add people to the shop's accounts");
        }
        Role role = roleRepository.findSystemRoleByKey(SERVICE_STAFF_ROLE_KEY)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "The service staff role is not set up yet"));
        return identityService.createUserWithoutSignIn(businessId, name, phone, role.getId(), branchId);
    }

    private UserResponse createLogin(
            String businessId, String name, String email, String pin, String phone, String branchId,
            TenantPrincipal caller) {
        if (!permissionService.hasPermission(caller.roleId(), "users.create")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not allowed to add people who can sign in");
        }
        Role role = roleRepository.findSystemRoleByKey(SERVICE_STAFF_ROLE_KEY)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "The service staff role is not set up yet"));
        return identityService.createUser(businessId, new CreateUserRequest(
                email.trim(), name, phone == null || phone.isBlank() ? null : phone.trim(), role.getId(),
                branchId == null || branchId.isBlank() ? null : branchId.trim(), null, null, pin.trim(), null, null));
    }

    /** A customer's first name is enough for the person about to serve them. */
    private static String firstName(String name) {
        if (name == null || name.isBlank()) {
            return "Customer";
        }
        return name.trim().split("\\s+")[0];
    }
}
