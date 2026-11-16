package zelisline.ub.platform.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.api.dto.PlatformBrandingSettingsResponse;
import zelisline.ub.platform.application.PlatformBrandingSettingsService;

/**
 * Super-admin platform branding. Secured by {@code /api/v1/super-admin/**}
 * (PERM_sa.console.full) in SecurityConfig. Currently the email logo used in
 * platform transactional mail.
 */
@Validated
@RestController
@RequestMapping("/api/v1/super-admin/platform/branding")
@RequiredArgsConstructor
public class SuperAdminPlatformBrandingController {

    private final PlatformBrandingSettingsService service;

    @GetMapping
    public PlatformBrandingSettingsResponse get() {
        return service.getForSuperAdmin();
    }

    @PostMapping(value = "/email-logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PlatformBrandingSettingsResponse> uploadEmailLogo(
            @RequestPart("file") MultipartFile file) {
        byte[] bytes = readUploadBytes(file);
        PlatformBrandingSettingsResponse body = service.uploadEmailLogo(bytes, file.getOriginalFilename());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @DeleteMapping("/email-logo")
    public PlatformBrandingSettingsResponse clearEmailLogo() {
        return service.clearEmailLogo();
    }

    private static byte[] readUploadBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a logo to upload");
        }
        try {
            return file.getBytes();
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded file");
        }
    }
}
