package zelisline.ub.payroll.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataIntegrityViolationException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.application.RequestPermissionService;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.payroll.api.dto.CreateStaffProfileRequest;
import zelisline.ub.payroll.api.dto.StaffProfileResponse;
import zelisline.ub.payroll.api.dto.UpdateStaffProfileRequest;
import zelisline.ub.payroll.domain.EmploymentStatus;
import zelisline.ub.payroll.domain.JoinPayMode;
import zelisline.ub.payroll.domain.StaffProfile;
import zelisline.ub.payroll.repository.StaffProfileRepository;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.repository.BranchRepository;

@Service
@RequiredArgsConstructor
public class StaffProfileService {

    public static final String PERM_HR_READ = "staff.hr.read";
    public static final String PERM_HR_UPDATE = "staff.hr.update";

    private final StaffProfileRepository staffProfileRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final BranchRepository branchRepository;
    private final RequestPermissionService permissionService;
    private final ObjectMapper objectMapper;

    @Transactional
    public StaffProfile ensureProfile(String businessId, String userId) {
        requireUserId(userId);
        return staffProfileRepository.findByBusinessIdAndUserId(businessId, userId)
                .orElseGet(() -> createDefault(businessId, userId));
    }

    @Transactional
    public StaffProfile save(StaffProfile profile) {
        return staffProfileRepository.save(profile);
    }

    @Transactional(readOnly = true)
    public StaffProfile requireProfileForUser(String businessId, String userId) {
        requireUserId(userId);
        return staffProfileRepository.findByBusinessIdAndUserId(businessId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff profile not found"));
    }

    @Transactional
    public StaffProfileResponse getOrCreateProfile(String businessId, String userId, TenantPrincipal caller) {
        User user = requireUser(businessId, userId);
        StaffProfile profile = ensureProfile(businessId, userId);
        return toResponse(profile, user, includePrivate(caller));
    }

    @Transactional(readOnly = true)
    public List<StaffProfileResponse> listProfiles(String businessId, TenantPrincipal caller) {
        boolean includePrivate = includePrivate(caller);
        // Lazy-create is not done for every user on list; only return existing profiles
        // plus ensure we surface users without a profile via empty-public defaults when needed.
        // For MVP roster: ensure profiles for all active users would be heavy; return existing
        // and let GET-by-user create. Also include users without profiles as synthetic cards.
        List<User> users = userRepository.pageByBusiness(businessId, org.springframework.data.domain.Pageable.unpaged())
                .getContent();
        Map<String, StaffProfile> byUser = new LinkedHashMap<>();
        List<StaffProfile> withoutLogin = new ArrayList<>();
        for (StaffProfile p : staffProfileRepository.findByBusinessIdOrderByDisplayNameAsc(businessId)) {
            if (p.getUserId() == null) {
                withoutLogin.add(p);
            } else {
                byUser.put(p.getUserId(), p);
            }
        }
        List<StaffProfileResponse> out = new ArrayList<>(users.size() + withoutLogin.size());
        for (User user : users) {
            StaffProfile profile = byUser.get(user.getId());
            out.add(profile == null
                    ? syntheticPublic(user, includePrivate)
                    : toResponse(profile, user, includePrivate));
        }
        // Staff with no login (a barber who never signs in) follow the account holders.
        for (StaffProfile profile : withoutLogin) {
            out.add(toResponse(profile, null, includePrivate));
        }
        return out;
    }

    /** Adds a staff member who has no login. They never appear in payroll runs, which are per user. */
    @Transactional
    public StaffProfileResponse createWithoutLogin(
            String businessId,
            CreateStaffProfileRequest body,
            TenantPrincipal caller
    ) {
        requireHrUpdate(caller);
        String name = body.displayName().trim();
        StaffProfile profile = new StaffProfile();
        profile.setBusinessId(businessId);
        profile.setUserId(null);
        profile.setDisplayName(name);
        profile.setTitle(blankToNull(body.title()));
        profile.setPhone(blankToNull(body.phone()));
        profile.setStartDate(body.startDate());
        profile.setEmploymentStatus(EmploymentStatus.ACTIVE);
        profile.setIncludeInPayroll(false);
        profile.setProrateJoinMonth(false);
        profile.setJoinPayMode(JoinPayMode.HALF);
        profile.setBookable(body.bookable() == null || body.bookable());
        saveLoginless(profile);
        return toResponse(profile, null, true);
    }

    /** Updates any staff profile of the business by its own id — the only way to reach one with no login. */
    @Transactional
    public StaffProfileResponse updateProfileById(
            String businessId,
            String profileId,
            UpdateStaffProfileRequest body,
            TenantPrincipal caller
    ) {
        requireHrUpdate(caller);
        StaffProfile profile = staffProfileRepository.findByIdAndBusinessId(profileId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff profile not found"));
        User user = profile.getUserId() == null ? null : requireUser(businessId, profile.getUserId());
        if (user == null && body.displayName() != null && body.displayName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A staff member needs a name");
        }
        if (user == null && Boolean.TRUE.equals(body.includeInPayroll())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Payroll runs are for staff with a login; this staff member has none");
        }
        applyUpdate(profile, body);
        if (user == null) {
            saveLoginless(profile);
        } else {
            staffProfileRepository.save(profile);
        }
        return toResponse(profile, user, true);
    }

    /**
     * Saves a staff member who has no login, keeping {@code loginlessNameKey} in step with their
     * name and status. Two current staff with no login cannot share a name: with no account behind
     * them the name is all the till has to tell them apart. The unique key, not this check, is what
     * holds under a double tap or two people adding "John" at once; the check only gives the
     * common case a clear message. A former (terminated) staff member holds no name, so re-hiring
     * them is checked like any rename.
     */
    private void saveLoginless(StaffProfile profile) {
        String name = profile.getDisplayName() == null ? "" : profile.getDisplayName().trim();
        boolean current = !EmploymentStatus.TERMINATED.equals(profile.getEmploymentStatus());
        String key = current && !name.isEmpty() ? name.toLowerCase(Locale.ROOT) : null;
        if (key != null) {
            boolean taken = profile.getId() == null
                    ? staffProfileRepository.existsByBusinessIdAndLoginlessNameKey(profile.getBusinessId(), key)
                    : staffProfileRepository.existsByBusinessIdAndLoginlessNameKeyAndIdNot(
                            profile.getBusinessId(), key, profile.getId());
            if (taken) {
                throw nameTaken(name);
            }
        }
        profile.setLoginlessNameKey(key);
        try {
            staffProfileRepository.saveAndFlush(profile);
        } catch (DataIntegrityViolationException ex) {
            throw nameTaken(name);
        }
    }

    private static ResponseStatusException nameTaken(String name) {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "A staff member called " + name + " already exists");
    }

    private static void requireUserId(String userId) {
        // A null id would match every staff member who has no login (user_id IS NULL).
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
    }

    private void requireHrUpdate(TenantPrincipal caller) {
        if (!permissionService.hasPermission(caller.roleId(), PERM_HR_UPDATE)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing staff.hr.update");
        }
    }

    @Transactional
    public StaffProfileResponse updateProfile(
            String businessId,
            String userId,
            UpdateStaffProfileRequest body,
            TenantPrincipal caller
    ) {
        requireHrUpdate(caller);
        User user = requireUser(businessId, userId);
        StaffProfile profile = ensureProfile(businessId, userId);
        applyUpdate(profile, body);
        staffProfileRepository.save(profile);
        return toResponse(profile, user, true);
    }

    private void applyUpdate(StaffProfile profile, UpdateStaffProfileRequest body) {
        if (body.displayName() != null) {
            profile.setDisplayName(blankToNull(body.displayName()));
        }
        if (body.title() != null) {
            profile.setTitle(blankToNull(body.title()));
        }
        if (body.photoUrl() != null) {
            profile.setPhotoUrl(blankToNull(body.photoUrl()));
        }
        if (body.startDate() != null) {
            profile.setStartDate(body.startDate());
        }
        if (body.employmentStatus() != null) {
            String status = body.employmentStatus().trim().toLowerCase();
            if (!EmploymentStatus.isValid(status)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid employment status");
            }
            profile.setEmploymentStatus(status);
        }
        if (body.includeInPayroll() != null) {
            profile.setIncludeInPayroll(body.includeInPayroll());
        }
        if (body.prorateJoinMonth() != null) {
            profile.setProrateJoinMonth(body.prorateJoinMonth());
            if (body.joinPayMode() == null) {
                profile.setJoinPayMode(JoinPayMode.fromLegacyProrateFlag(body.prorateJoinMonth()));
            }
        }
        if (body.joinPayMode() != null) {
            try {
                String mode = JoinPayMode.normalize(body.joinPayMode());
                profile.setJoinPayMode(mode);
                profile.setProrateJoinMonth(JoinPayMode.PRORATE.equals(mode));
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
            }
        }
        if (body.phone() != null) {
            profile.setPhone(blankToNull(body.phone()));
        }
        if (body.address() != null) {
            profile.setAddress(blankToNull(body.address()));
        }
        if (body.nationalId() != null) {
            profile.setNationalId(blankToNull(body.nationalId()));
        }
        if (body.employeeCode() != null) {
            profile.setEmployeeCode(blankToNull(body.employeeCode()));
        }
        if (body.emergencyContactName() != null) {
            profile.setEmergencyContactName(blankToNull(body.emergencyContactName()));
        }
        if (body.emergencyContactPhone() != null) {
            profile.setEmergencyContactPhone(blankToNull(body.emergencyContactPhone()));
        }
        if (body.bankDetails() != null) {
            profile.setBankDetails(writeJson(body.bankDetails()));
        }
        if (body.notes() != null) {
            profile.setNotes(blankToNull(body.notes()));
        }
        if (body.bookable() != null) {
            profile.setBookable(body.bookable());
        }
        if (body.usualStationNo() != null) {
            profile.setUsualStationNo(body.usualStationNo() == 0 ? null : body.usualStationNo());
        }
    }

    private StaffProfile createDefault(String businessId, String userId) {
        User user = requireUser(businessId, userId);
        StaffProfile profile = new StaffProfile();
        profile.setBusinessId(businessId);
        profile.setUserId(userId);
        profile.setDisplayName(user.getName());
        profile.setPhone(user.getPhone());
        profile.setEmploymentStatus(EmploymentStatus.ACTIVE);
        profile.setIncludeInPayroll(true);
        profile.setProrateJoinMonth(false);
        profile.setJoinPayMode(JoinPayMode.HALF);
        try {
            // Flush inside the try so a concurrent first-touch (e.g. two requests
            // materialising the same new profile) surfaces as a retryable error
            // instead of a raw constraint-violation 500 at commit time.
            return staffProfileRepository.saveAndFlush(profile);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Staff profile was just created by another action — try again");
        }
    }

    private User requireUser(String businessId, String userId) {
        return userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private boolean includePrivate(TenantPrincipal caller) {
        return permissionService.hasPermission(caller.roleId(), PERM_HR_READ);
    }

    private StaffProfileResponse toResponse(StaffProfile profile, User user, boolean includePrivate) {
        // user is null for staff with no login.
        String branchId = user != null ? user.getBranchId() : null;
        String branchName = resolveBranchName(branchId);
        String roleName = user != null ? resolveRoleName(user.getRoleId()) : null;
        String display = profile.getDisplayName() != null && !profile.getDisplayName().isBlank()
                ? profile.getDisplayName()
                : user != null ? user.getName() : "Staff";

        StaffProfileResponse.PublicFields pub = new StaffProfileResponse.PublicFields(
                display,
                profile.getTitle(),
                profile.getPhotoUrl(),
                profile.getStartDate(),
                profile.getEmploymentStatus(),
                profile.isIncludeInPayroll(),
                profile.isProrateJoinMonth(),
                resolveJoinPayMode(profile),
                profile.isBookable(),
                profile.getUsualStationNo()
        );

        StaffProfileResponse.PrivateFields priv = null;
        if (includePrivate) {
            priv = new StaffProfileResponse.PrivateFields(
                    profile.getPhone(),
                    profile.getAddress(),
                    profile.getNationalId(),
                    profile.getEmployeeCode(),
                    profile.getEmergencyContactName(),
                    profile.getEmergencyContactPhone(),
                    readJson(profile.getBankDetails()),
                    profile.getNotes()
            );
        }

        return new StaffProfileResponse(
                profile.getId(),
                user != null ? user.getId() : null,
                branchId,
                branchName,
                user != null ? user.getName() : null,
                roleName,
                pub,
                priv,
                profile.getCreatedAt(),
                profile.getUpdatedAt()
        );
    }

    private StaffProfileResponse syntheticPublic(User user, boolean includePrivate) {
        String display = user.getName();
        StaffProfileResponse.PublicFields pub = new StaffProfileResponse.PublicFields(
                display,
                null,
                null,
                null,
                EmploymentStatus.ACTIVE,
                true,
                true,
                JoinPayMode.HALF,
                false,
                null
        );
        StaffProfileResponse.PrivateFields priv = null;
        if (includePrivate) {
            priv = new StaffProfileResponse.PrivateFields(
                    user.getPhone(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    Map.of(),
                    null
            );
        }
        return new StaffProfileResponse(
                null,
                user.getId(),
                user.getBranchId(),
                resolveBranchName(user.getBranchId()),
                user.getName(),
                resolveRoleName(user.getRoleId()),
                pub,
                priv,
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }

    private String resolveBranchName(String branchId) {
        if (branchId == null || branchId.isBlank()) {
            return null;
        }
        return branchRepository.findById(branchId)
                .filter(b -> b.getDeletedAt() == null)
                .map(Branch::getName)
                .orElse(null);
    }

    private String resolveRoleName(String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        return roleRepository.findByIdAndDeletedAtIsNull(roleId)
                .map(Role::getName)
                .orElse(null);
    }

    private static String resolveJoinPayMode(StaffProfile profile) {
        String mode = profile.getJoinPayMode();
        if (JoinPayMode.isValid(mode)) {
            return JoinPayMode.normalize(mode);
        }
        return JoinPayMode.fromLegacyProrateFlag(profile.isProrateJoinMonth());
    }

    private Map<String, Object> readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String writeJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid bank details JSON");
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Transactional(readOnly = true)
    public Optional<StaffProfile> findByUser(String businessId, String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return staffProfileRepository.findByBusinessIdAndUserId(businessId, userId);
    }

    @Transactional(readOnly = true)
    public List<StaffProfile> findProfilesByBusiness(String businessId) {
        return staffProfileRepository.findByBusinessIdOrderByDisplayNameAsc(businessId);
    }

    /** Ids of current staff who perform services and can be the performer on a service sale line. */
    @Transactional(readOnly = true)
    public Set<String> findBookableProfileIds(String businessId) {
        return new HashSet<>(staffProfileRepository.findBookableIdsByBusinessId(
                businessId, EmploymentStatus.TERMINATED));
    }

    /** Sets whether a staff member can take the next customer. Unknown values are refused. */
    @Transactional
    public String setFloorStatus(String businessId, String staffProfileId, String status) {
        StaffProfile profile = staffProfileRepository.findByIdAndBusinessId(staffProfileId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff member not found"));
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!"AVAILABLE".equals(normalized) && !"ON_BREAK".equals(normalized) && !"OFF".equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status must be AVAILABLE, ON_BREAK or OFF");
        }
        profile.setFloorStatus(normalized);
        staffProfileRepository.save(profile);
        return normalized;
    }

    /** Adds a period when this person cannot be booked. Periods already over are dropped as it is saved. */
    @Transactional
    public List<zelisline.ub.serviceops.domain.StaffTimeOff.Period> addTimeOff(
            String businessId, String staffProfileId, java.time.Instant from, java.time.Instant to, String reason) {
        StaffProfile profile = staffProfileRepository.findByIdAndBusinessIdForUpdate(staffProfileId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff member not found"));
        if (from == null || to == null || !to.isAfter(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The end must be after the start");
        }
        if (java.time.Duration.between(from, to).toDays() > 366) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Time off can be at most a year long");
        }
        java.time.Instant now = java.time.Instant.now();
        List<zelisline.ub.serviceops.domain.StaffTimeOff.Period> periods =
                zelisline.ub.serviceops.domain.StaffTimeOff.decode(profile.getTimeOff());
        periods.removeIf(p -> !p.to().isAfter(now));
        if (periods.size() >= zelisline.ub.serviceops.domain.StaffTimeOff.MAX_PERIODS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Remove an old period before adding another");
        }
        periods.add(new zelisline.ub.serviceops.domain.StaffTimeOff.Period(from, to, reason));
        profile.setTimeOff(zelisline.ub.serviceops.domain.StaffTimeOff.encode(periods));
        staffProfileRepository.save(profile);
        return zelisline.ub.serviceops.domain.StaffTimeOff.decode(profile.getTimeOff());
    }

    /** Removes the period that starts at {@code from}. Removing one that is not there changes nothing. */
    @Transactional
    public List<zelisline.ub.serviceops.domain.StaffTimeOff.Period> removeTimeOff(
            String businessId, String staffProfileId, java.time.Instant from) {
        StaffProfile profile = staffProfileRepository.findByIdAndBusinessIdForUpdate(staffProfileId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff member not found"));
        List<zelisline.ub.serviceops.domain.StaffTimeOff.Period> periods =
                zelisline.ub.serviceops.domain.StaffTimeOff.decode(profile.getTimeOff());
        periods.removeIf(p -> p.from().getEpochSecond() == from.getEpochSecond());
        profile.setTimeOff(zelisline.ub.serviceops.domain.StaffTimeOff.encode(periods));
        staffProfileRepository.save(profile);
        return periods;
    }

    /** Time off by staff profile id, for current customer-serving staff who have any. */
    @Transactional(readOnly = true)
    public Map<String, List<zelisline.ub.serviceops.domain.StaffTimeOff.Period>> timeOff(String businessId) {
        Map<String, List<zelisline.ub.serviceops.domain.StaffTimeOff.Period>> out = new LinkedHashMap<>();
        for (StaffProfile profile : staffProfileRepository.findByBusinessIdOrderByDisplayNameAsc(businessId)) {
            if (profile.isBookable() && profile.getTimeOff() != null) {
                out.put(profile.getId(), zelisline.ub.serviceops.domain.StaffTimeOff.decode(profile.getTimeOff()));
            }
        }
        return out;
    }

    /** Whether this person is off for any part of a window. */
    @Transactional(readOnly = true)
    public boolean isOff(String businessId, String staffProfileId, java.time.Instant start, java.time.Instant end) {
        return staffProfileRepository.findByIdAndBusinessId(staffProfileId, businessId)
                .map(p -> zelisline.ub.serviceops.domain.StaffTimeOff.decode(p.getTimeOff()).stream()
                        .anyMatch(period -> period.overlaps(start, end)))
                .orElse(false);
    }

    /** Floor status by staff profile id for current, customer-serving staff ({@code AVAILABLE} when unset). */
    @Transactional(readOnly = true)
    public Map<String, String> floorStatuses(String businessId) {
        Map<String, String> out = new LinkedHashMap<>();
        for (StaffProfile profile : staffProfileRepository.findByBusinessIdOrderByDisplayNameAsc(businessId)) {
            if (profile.isBookable() && !EmploymentStatus.TERMINATED.equals(profile.getEmploymentStatus())) {
                out.put(profile.getId(), profile.getFloorStatus() == null ? "AVAILABLE" : profile.getFloorStatus());
            }
        }
        return out;
    }

    // ------------------------------------------------------------ commission plans (ADR 0014)

    /** Every staff profile of the business with its commission plan, current staff first by name. */
    @Transactional(readOnly = true)
    public List<StaffCommissionPlan> commissionPlans(String businessId) {
        List<StaffProfile> profiles = staffProfileRepository.findByBusinessIdOrderByDisplayNameAsc(businessId);
        Map<String, String> names = displayNamesByIds(
                businessId, profiles.stream().map(StaffProfile::getId).toList());
        List<StaffCommissionPlan> out = new ArrayList<>(profiles.size());
        for (StaffProfile profile : profiles) {
            out.add(toPlan(profile, names.getOrDefault(profile.getId(), "Staff")));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Optional<StaffCommissionPlan> commissionPlan(String businessId, String staffProfileId) {
        if (staffProfileId == null || staffProfileId.isBlank()) {
            return Optional.empty();
        }
        return staffProfileRepository.findByIdAndBusinessId(staffProfileId, businessId)
                .map(profile -> toPlan(profile, displayNamesByIds(businessId, List.of(profile.getId()))
                        .getOrDefault(profile.getId(), "Staff")));
    }

    /** The commission plan of the staff profile behind a login, if that user has one. */
    @Transactional(readOnly = true)
    public Optional<StaffCommissionPlan> commissionPlanForUser(String businessId, String userId) {
        return findByUser(businessId, userId)
                .map(profile -> toPlan(profile, profile.getDisplayName() == null ? "Staff" : profile.getDisplayName()));
    }

    /**
     * Sets how a staff member earns. {@code type} null or blank means no commission on services.
     * Applies to sales from now on; earnings already recorded keep the rule they were made with.
     */
    @Transactional
    public StaffCommissionPlan setCommissionPlan(
            String businessId,
            String staffProfileId,
            String type,
            java.math.BigDecimal rate,
            java.math.BigDecimal productRate
    ) {
        StaffProfile profile = staffProfileRepository.findByIdAndBusinessId(staffProfileId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff member not found"));
        String normalized = type == null || type.isBlank() ? null : type.trim().toUpperCase(Locale.ROOT);
        if (normalized != null && !"PERCENT".equals(normalized) && !"FIXED".equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Commission type must be PERCENT or FIXED");
        }
        if (normalized == null) {
            profile.setCommissionType(null);
            profile.setCommissionRate(null);
        } else {
            if (rate == null || rate.signum() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a commission rate of zero or more");
            }
            if ("PERCENT".equals(normalized) && rate.compareTo(new java.math.BigDecimal("100")) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Commission percent cannot be more than 100");
            }
            profile.setCommissionType(normalized);
            profile.setCommissionRate(rate.setScale(2, java.math.RoundingMode.HALF_UP));
        }
        if (productRate != null) {
            if (productRate.signum() < 0 || productRate.compareTo(new java.math.BigDecimal("100")) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Product commission must be a percent between 0 and 100");
            }
            profile.setProductCommissionRate(productRate.setScale(2, java.math.RoundingMode.HALF_UP));
        } else {
            profile.setProductCommissionRate(null);
        }
        staffProfileRepository.save(profile);
        return toPlan(profile, displayNamesByIds(businessId, List.of(profile.getId()))
                .getOrDefault(profile.getId(), "Staff"));
    }

    /** Locks the staff member's row for the rest of the transaction (serialises payouts). */
    @Transactional
    public void lockForLedger(String businessId, String staffProfileId) {
        staffProfileRepository.findByIdAndBusinessIdForUpdate(staffProfileId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff member not found"));
    }

    private static StaffCommissionPlan toPlan(StaffProfile profile, String displayName) {
        return new StaffCommissionPlan(
                profile.getId(),
                profile.getUserId(),
                displayName,
                profile.isBookable(),
                !EmploymentStatus.TERMINATED.equals(profile.getEmploymentStatus()),
                profile.getCommissionType(),
                profile.getCommissionRate(),
                profile.getProductCommissionRate());
    }

    /** Display names by staff profile id, for the given ids that belong to this business. */
    @Transactional(readOnly = true)
    public Map<String, String> displayNamesByIds(String businessId, java.util.Collection<String> profileIds) {
        Map<String, String> out = new LinkedHashMap<>();
        if (profileIds == null || profileIds.isEmpty()) {
            return out;
        }
        for (StaffProfile profile : staffProfileRepository.findAllById(profileIds)) {
            if (!businessId.equals(profile.getBusinessId())) {
                continue;
            }
            String name = profile.getDisplayName();
            if ((name == null || name.isBlank()) && profile.getUserId() != null) {
                name = userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(profile.getUserId(), businessId)
                        .map(User::getName)
                        .orElse(null);
            }
            out.put(profile.getId(), name == null || name.isBlank() ? "Staff" : name.trim());
        }
        return out;
    }

    /** Ids of the business's staff profiles that are not terminated (active or on leave). */
    @Transactional(readOnly = true)
    public Set<String> findCurrentProfileIds(String businessId) {
        return new HashSet<>(staffProfileRepository.findIdsByBusinessIdAndEmploymentStatusNot(
                businessId, EmploymentStatus.TERMINATED));
    }
}
