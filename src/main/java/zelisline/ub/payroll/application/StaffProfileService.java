package zelisline.ub.payroll.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.application.RequestPermissionService;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.payroll.api.dto.StaffProfileResponse;
import zelisline.ub.payroll.api.dto.UpdateStaffProfileRequest;
import zelisline.ub.payroll.domain.EmploymentStatus;
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
        return staffProfileRepository.findByBusinessIdAndUserId(businessId, userId)
                .orElseGet(() -> createDefault(businessId, userId));
    }

    @Transactional(readOnly = true)
    public StaffProfile requireProfileForUser(String businessId, String userId) {
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
        for (StaffProfile p : staffProfileRepository.findByBusinessIdOrderByDisplayNameAsc(businessId)) {
            byUser.put(p.getUserId(), p);
        }
        return users.stream()
                .map(user -> {
                    StaffProfile profile = byUser.get(user.getId());
                    if (profile == null) {
                        return syntheticPublic(user, includePrivate);
                    }
                    return toResponse(profile, user, includePrivate);
                })
                .toList();
    }

    @Transactional
    public StaffProfileResponse updateProfile(
            String businessId,
            String userId,
            UpdateStaffProfileRequest body,
            TenantPrincipal caller
    ) {
        if (!permissionService.hasPermission(caller.roleId(), PERM_HR_UPDATE)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing staff.hr.update");
        }
        User user = requireUser(businessId, userId);
        StaffProfile profile = ensureProfile(businessId, userId);

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

        staffProfileRepository.save(profile);
        return toResponse(profile, user, true);
    }

    private StaffProfile createDefault(String businessId, String userId) {
        User user = requireUser(businessId, userId);
        StaffProfile profile = new StaffProfile();
        profile.setBusinessId(businessId);
        profile.setUserId(userId);
        profile.setDisplayName(user.getName());
        profile.setPhone(user.getPhone());
        profile.setEmploymentStatus(EmploymentStatus.ACTIVE);
        return staffProfileRepository.save(profile);
    }

    private User requireUser(String businessId, String userId) {
        return userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private boolean includePrivate(TenantPrincipal caller) {
        return permissionService.hasPermission(caller.roleId(), PERM_HR_READ);
    }

    private StaffProfileResponse toResponse(StaffProfile profile, User user, boolean includePrivate) {
        String branchName = resolveBranchName(user.getBranchId());
        String roleName = resolveRoleName(user.getRoleId());
        String display = profile.getDisplayName() != null && !profile.getDisplayName().isBlank()
                ? profile.getDisplayName()
                : user.getName();

        StaffProfileResponse.PublicFields pub = new StaffProfileResponse.PublicFields(
                display,
                profile.getTitle(),
                profile.getPhotoUrl(),
                profile.getStartDate(),
                profile.getEmploymentStatus()
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
                user.getId(),
                user.getBranchId(),
                branchName,
                user.getName(),
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
                EmploymentStatus.ACTIVE
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
        return staffProfileRepository.findByBusinessIdAndUserId(businessId, userId);
    }
}
