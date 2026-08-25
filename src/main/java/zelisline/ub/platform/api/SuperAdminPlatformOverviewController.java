package zelisline.ub.platform.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.api.dto.PlatformOverviewResponse;
import zelisline.ub.platform.overview.PlatformOverviewService;

@RestController
@RequestMapping("/api/v1/super-admin/platform/overview")
@RequiredArgsConstructor
public class SuperAdminPlatformOverviewController {

    private final PlatformOverviewService platformOverviewService;

    @GetMapping
    public PlatformOverviewResponse overview() {
        return platformOverviewService.load();
    }
}
