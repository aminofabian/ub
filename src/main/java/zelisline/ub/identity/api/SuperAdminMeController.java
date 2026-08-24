package zelisline.ub.identity.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.identity.api.dto.PasswordChangeRequest;
import zelisline.ub.identity.api.dto.SuperAdminLoginResponse;
import zelisline.ub.identity.api.dto.SuperAdminTestSmsResponse;
import zelisline.ub.identity.api.dto.UpdateSuperAdminProfileRequest;
import zelisline.ub.identity.domain.SuperAdmin;
import zelisline.ub.identity.repository.SuperAdminRepository;
import zelisline.ub.messaging.application.CustomerMessageDispatcher;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import org.springframework.security.crypto.password.PasswordEncoder;

@Validated
@RestController
@RequestMapping("/api/v1/super-admin/me")
@RequiredArgsConstructor
public class SuperAdminMeController {

    private final SuperAdminRepository superAdminRepository;
    private final PasswordEncoder passwordEncoder;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final CustomerMessageDispatcher customerMessageDispatcher;

    @GetMapping
    public SuperAdminLoginResponse getMe() {
        SuperAdmin admin = requireSuperAdmin();
        return toResponse(admin);
    }

    @PatchMapping
    public SuperAdminLoginResponse updateProfile(@Valid @RequestBody UpdateSuperAdminProfileRequest body) {
        SuperAdmin admin = requireSuperAdmin();
        if (body.name() != null) {
            String name = body.name().trim();
            if (name.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name cannot be blank");
            }
            admin.setName(name);
        }
        if (body.phone() != null) {
            String phone = body.phone().trim();
            if (phone.isBlank()) {
                admin.setPhone(null);
            } else {
                String normalized = zelisline.ub.payments.application.StkPhoneNormalizer.normalize(phone);
                if (normalized == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Enter a valid phone number (e.g. 0712 345 678)");
                }
                admin.setPhone(normalized);
            }
        }
        return toResponse(superAdminRepository.save(admin));
    }

    private static SuperAdminLoginResponse toResponse(SuperAdmin admin) {
        return new SuperAdminLoginResponse(null, admin.getId(), admin.getEmail(), admin.getName(), admin.getPhone());
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

    /** Verify platform SMS delivery to the super admin's own phone (adoption alerts). */
    @PostMapping("/test-sms")
    public SuperAdminTestSmsResponse testSms() {
        SuperAdmin admin = requireSuperAdmin();
        String digits = StkPhoneNormalizer.normalize(admin.getPhone());
        if (digits == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Set an SMS alert phone in your profile first");
        }
        TenantMessagingConfig messaging = messagingSettingsService.resolvePlatformForContactReply();
        if (!messaging.smsConfigured()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Platform SMS is not configured — add Sozuri, TextSMS, or Africa's Talking under Platform → Integrations");
        }
        String message = "Palmart test SMS — super-admin adoption alerts are working.";
        CustomerMessageDispatcher.DeliveryResult delivery =
                customerMessageDispatcher.deliverSmsOnly(messaging, digits, message);
        if ("failed".equals(delivery.outcome()) || "skipped".equals(delivery.outcome())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    delivery.detail() != null && !delivery.detail().isBlank()
                            ? delivery.detail()
                            : "Test SMS could not be delivered");
        }
        return new SuperAdminTestSmsResponse(
                delivery.channel(),
                delivery.outcome(),
                delivery.detail(),
                maskPhone(admin.getPhone()));
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() <= 6) {
            return "***";
        }
        return phone.substring(0, 4) + "…" + phone.substring(phone.length() - 2);
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
