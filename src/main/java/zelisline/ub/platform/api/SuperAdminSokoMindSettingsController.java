package zelisline.ub.platform.api;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import zelisline.ub.platform.api.dto.SokoMindSettingsResponse;
import zelisline.ub.platform.api.dto.UpdateSokoMindSettingsRequest;
import zelisline.ub.platform.application.PlatformSokoMindSettingsService;

@Validated
@RestController
@RequestMapping("/api/v1/super-admin/platform/sokomind")
@RequiredArgsConstructor
public class SuperAdminSokoMindSettingsController {

    private final PlatformSokoMindSettingsService platformSokoMindSettingsService;

    @GetMapping
    public SokoMindSettingsResponse get() {
        return platformSokoMindSettingsService.getForSuperAdmin();
    }

    @PutMapping
    public SokoMindSettingsResponse update(@Valid @RequestBody UpdateSokoMindSettingsRequest body) {
        return platformSokoMindSettingsService.update(body);
    }
}
