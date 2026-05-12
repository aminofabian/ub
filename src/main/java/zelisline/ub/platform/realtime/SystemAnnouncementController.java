package zelisline.ub.platform.realtime;

import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@RestController
@RequestMapping("/api/v1/admin")
public class SystemAnnouncementController {

    private final ApplicationEventPublisher eventPublisher;

    public SystemAnnouncementController(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @PostMapping("/announcements")
    @PreAuthorize("hasPermission(null, 'business.manage_settings')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void broadcast(@RequestBody Map<String, String> body, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String title = body.getOrDefault("title", "Announcement");
        String bodyText = body.getOrDefault("body", "");
        String level = body.getOrDefault("level", "INFO");

        eventPublisher.publishEvent(
                new RealtimeBridge.SystemAnnouncementEvent(businessId, title, bodyText, level));
    }
}
