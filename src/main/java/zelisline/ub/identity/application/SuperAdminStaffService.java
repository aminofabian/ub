package zelisline.ub.identity.application;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.domain.SuperAdmin;
import zelisline.ub.identity.domain.SuperAdminDeskRoles;
import zelisline.ub.identity.repository.SuperAdminRepository;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.serving.api.dto.ServingDtos;
import zelisline.ub.serving.application.ServingTicketService;

@Service
@RequiredArgsConstructor
public class SuperAdminStaffService {

    private final SuperAdminRepository superAdminRepository;
    private final PasswordEncoder passwordEncoder;
    private final ServingTicketService servingTicketService;

    public List<ServingDtos.StaffRow> list(SuperAdmin actor) {
        return superAdminRepository.findAll().stream()
                .sorted(Comparator.comparing(SuperAdmin::getName, String.CASE_INSENSITIVE_ORDER))
                .map(row -> toRow(row, actor.getId()))
                .toList();
    }

    @Transactional
    public ServingDtos.InviteStaffResponse invite(SuperAdmin actor, ServingDtos.InviteStaffRequest request) {
        assertCanManage(actor);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Staff body is required");
        }
        String name = requireName(request.name());
        String email = requireEmail(request.email());
        if (superAdminRepository.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A staff account already uses that email");
        }
        String deskRole = SuperAdminDeskRoles.normalize(request.deskRole() == null ? SuperAdminDeskRoles.AGENT : request.deskRole());
        if (SuperAdminDeskRoles.OWNER.equals(deskRole) && !SuperAdminDeskRoles.isOwner(actor.getDeskRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an owner can invite another owner");
        }
        String password = request.password() == null ? "" : request.password().trim();
        if (password.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Temporary password must be at least 8 characters");
        }

        SuperAdmin staff = new SuperAdmin();
        staff.setName(name);
        staff.setEmail(email);
        staff.setPhone(normalizePhone(request.phone()));
        staff.setPasswordHash(passwordEncoder.encode(password));
        staff.setActive(true);
        staff.setDeskRole(deskRole);
        superAdminRepository.save(staff);
        return new ServingDtos.InviteStaffResponse(toRow(staff, actor.getId()), password);
    }

    @Transactional
    public ServingDtos.StaffRow patch(SuperAdmin actor, String staffId, ServingDtos.PatchStaffRequest request) {
        assertCanManage(actor);
        SuperAdmin staff = superAdminRepository.findById(staffId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff not found"));
        if (request == null) {
            return toRow(staff, actor.getId());
        }
        if (request.name() != null) {
            staff.setName(requireName(request.name()));
        }
        if (request.phone() != null) {
            staff.setPhone(normalizePhone(request.phone()));
        }
        if (request.deskRole() != null) {
            String next = SuperAdminDeskRoles.normalize(request.deskRole());
            if (SuperAdminDeskRoles.OWNER.equals(staff.getDeskRole())
                    && !SuperAdminDeskRoles.OWNER.equals(next)
                    && countActiveOwners() <= 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Keep at least one owner");
            }
            if (SuperAdminDeskRoles.OWNER.equals(next) && !SuperAdminDeskRoles.isOwner(actor.getDeskRole())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an owner can grant owner");
            }
            staff.setDeskRole(next);
        }
        if (request.active() != null) {
            if (!request.active()) {
                if (actor.getId().equals(staff.getId())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot deactivate yourself");
                }
                if (SuperAdminDeskRoles.isOwner(staff.getDeskRole()) && countActiveOwners() <= 1) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Keep at least one owner");
                }
                Map<String, Integer> load = servingTicketService.loadFor(staff.getId());
                int open = load.getOrDefault("openCount", 0) + load.getOrDefault("waitingCount", 0);
                if (open > 0) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Reassign open tickets before deactivating this person");
                }
            }
            staff.setActive(request.active());
        }
        return toRow(superAdminRepository.save(staff), actor.getId());
    }

    private ServingDtos.StaffRow toRow(SuperAdmin staff, String actorId) {
        Map<String, Integer> load = servingTicketService.loadFor(staff.getId());
        return new ServingDtos.StaffRow(
                staff.getId(),
                staff.getEmail(),
                staff.getName(),
                staff.getPhone(),
                SuperAdminDeskRoles.normalizeOrOwner(staff.getDeskRole()),
                staff.isActive(),
                staff.getLastLoginAt(),
                staff.getCreatedAt(),
                load.getOrDefault("openCount", 0),
                load.getOrDefault("waitingCount", 0),
                actorId.equals(staff.getId())
        );
    }

    private long countActiveOwners() {
        return superAdminRepository.findAll().stream()
                .filter(SuperAdmin::isActive)
                .filter(s -> SuperAdminDeskRoles.isOwner(s.getDeskRole()))
                .count();
    }

    private static void assertCanManage(SuperAdmin actor) {
        if (!SuperAdminDeskRoles.canManageStaff(actor.getDeskRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot manage serving staff");
        }
    }

    private static String requireName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name is required");
        }
        return name;
    }

    private static String requireEmail(String raw) {
        String email = raw == null ? "" : raw.trim().toLowerCase();
        if (email.isEmpty() || !email.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is required");
        }
        return email;
    }

    private static String normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = StkPhoneNormalizer.normalize(raw);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid phone number");
        }
        return normalized;
    }
}
