package zelisline.ub.tenancy.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.dto.BrandingPatchRequest;
import zelisline.ub.tenancy.api.dto.BusinessResponse;
import zelisline.ub.tenancy.application.TenancyService;

/**
 * Tenant-self-service branding asset uploads. Plays well with the JSON-only
 * branding patch on {@code PATCH /api/v1/businesses/me} (text fields, colors,
 * URLs); this controller handles binary uploads that don't fit into JSON.
 */
@Validated
@RestController
@RequestMapping("/api/v1/businesses/me/branding")
@RequiredArgsConstructor
public class MyBrandingController {

    private static final String MANAGE_SETTINGS = "hasPermission(null, 'business.manage_settings')";

    private final TenancyService tenancyService;

    @PatchMapping
    @PreAuthorize(MANAGE_SETTINGS)
    public BusinessResponse patchBranding(
            @Valid @RequestBody BrandingPatchRequest patch,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return tenancyService.updateBrandingForTenant(
                TenantRequestIds.resolveBusinessId(request),
                patch
        );
    }

    @PostMapping(value = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(MANAGE_SETTINGS)
    public ResponseEntity<BusinessResponse> uploadLogo(
            @RequestPart("file") MultipartFile file,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        byte[] bytes = readBytes(file);
        BusinessResponse body = tenancyService.uploadBrandingLogo(
                TenantRequestIds.resolveBusinessId(request),
                bytes,
                file.getOriginalFilename()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @DeleteMapping("/logo")
    @PreAuthorize(MANAGE_SETTINGS)
    public BusinessResponse clearLogo(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return tenancyService.clearBrandingLogo(TenantRequestIds.resolveBusinessId(request));
    }

    private static byte[] readBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty logo file");
        }
        try {
            return file.getBytes();
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded file");
        }
    }
}
