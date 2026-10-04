package zelisline.ub.platform.api;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import zelisline.ub.platform.api.dto.MediaStorageSettingsResponse;
import zelisline.ub.platform.api.dto.UpdateMediaStorageSettingsRequest;
import zelisline.ub.platform.application.MediaStorageSettingsService;

@Validated
@RestController
@RequestMapping("/api/v1/super-admin/platform/media-storage")
@RequiredArgsConstructor
public class SuperAdminMediaStorageController {

    private final MediaStorageSettingsService mediaStorageSettingsService;

    @GetMapping
    public MediaStorageSettingsResponse get() {
        return mediaStorageSettingsService.getForSuperAdmin();
    }

    @PutMapping
    public MediaStorageSettingsResponse update(@Valid @RequestBody UpdateMediaStorageSettingsRequest body) {
        return mediaStorageSettingsService.update(body);
    }
}
