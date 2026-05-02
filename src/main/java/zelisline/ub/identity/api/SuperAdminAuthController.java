package zelisline.ub.identity.api;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.api.dto.SuperAdminLoginRequest;
import zelisline.ub.identity.api.dto.SuperAdminLoginResponse;
import zelisline.ub.identity.application.SuperAdminAuthService;

@Validated
@RestController
@RequestMapping("/api/v1/super-admin/auth")
@RequiredArgsConstructor
public class SuperAdminAuthController {

    private final SuperAdminAuthService superAdminAuthService;

    @PostMapping("/login")
    public SuperAdminLoginResponse login(@Valid @RequestBody SuperAdminLoginRequest request) {
        return superAdminAuthService.login(request);
    }
}
