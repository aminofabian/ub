package zelisline.ub.identity.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.api.dto.PasswordChangeRequest;
import zelisline.ub.identity.api.dto.SuperAdminLoginResponse;
import zelisline.ub.identity.domain.SuperAdmin;
import zelisline.ub.identity.repository.SuperAdminRepository;
import org.springframework.security.crypto.password.PasswordEncoder;

@Validated
@RestController
@RequestMapping("/api/v1/super-admin/me")
@RequiredArgsConstructor
public class SuperAdminMeController {

    private final SuperAdminRepository superAdminRepository;
    private final PasswordEncoder passwordEncoder;

    @GetMapping
    public SuperAdminLoginResponse getMe() {
        SuperAdmin admin = requireSuperAdmin();
        return new SuperAdminLoginResponse(null, admin.getId(), admin.getEmail(), admin.getName());
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody PasswordChangeRequest request) {
        SuperAdmin admin = requireSuperAdmin();

        if (!passwordEncoder.matches(request.currentPassword(), admin.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Current password is incorrect");
        }

        admin.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        superAdminRepository.save(admin);
    }

    private SuperAdmin requireSuperAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        String id = (String) authentication.getPrincipal();
        return superAdminRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Super admin not found"));
    }
}
