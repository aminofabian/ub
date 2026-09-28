package zelisline.ub.desktop.api;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.desktop.api.dto.DesktopLogFile;
import zelisline.ub.desktop.api.dto.DesktopStorageStatus;
import zelisline.ub.desktop.application.DesktopDiagnosticsService;
import zelisline.ub.desktop.logs.DesktopLogReporter;
import zelisline.ub.platform.security.CurrentTenantUser;

/**
 * Read-only diagnostics for Settings → Desktop: where the till keeps its data,
 * how much disk is left, and bounded tails of its own logs — so an operator can
 * see (or paste to support) a real error without a filesystem trip.
 *
 * <p>Owner/admin/manager only ({@code business.manage_settings}); the response
 * exposes the local data path and log contents, so a machine credential must not
 * read it ({@link CurrentTenantUser#requireHuman}).
 */
@RestController
@Profile("desktop")
@RequestMapping("/api/v1/desktop/diagnostics")
@RequiredArgsConstructor
public class DesktopDiagnosticsController {

    private final DesktopDiagnosticsService diagnostics;

    /**
     * Optional: the reporter is {@code @ConditionalOnProperty}-gated, so it may
     * legitimately be absent — the endpoint then reports sending is disabled
     * rather than failing bean wiring at startup.
     */
    private final ObjectProvider<DesktopLogReporter> logReporter;

    @GetMapping("/storage")
    @PreAuthorize("hasPermission(null, 'business.manage_settings')")
    public DesktopStorageStatus storage(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return diagnostics.storage();
    }

    @GetMapping("/logs")
    @PreAuthorize("hasPermission(null, 'business.manage_settings')")
    public List<DesktopLogFile> logs(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return diagnostics.logs();
    }

    /**
     * Send the same gzipped log-tail bundle the scheduled reporter ships, on
     * demand, so an operator can push it to support without waiting for the next
     * scheduled run. Honest by design: with no ingest key configured the till has
     * nowhere to send, and the result says so.
     */
    @PostMapping("/send-logs")
    @PreAuthorize("hasPermission(null, 'business.manage_settings')")
    public DesktopLogReporter.LogSendResult sendLogs(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        DesktopLogReporter reporter = logReporter.getIfAvailable();
        if (reporter == null) {
            return new DesktopLogReporter.LogSendResult(
                false, "Log sending is disabled on this till.");
        }
        return reporter.sendNow();
    }
}
