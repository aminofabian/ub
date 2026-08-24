package zelisline.ub.platform.loadtest;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Super Admin → Platform → Load test console backend.
 *
 * <p>Secured by {@code /api/v1/super-admin/** → ROLE_SUPER_ADMIN} in
 * {@code SecurityConfig}. Runs staircase load tests against this instance
 * (self-test over {@code 127.0.0.1}) and exposes live capacity gauges plus
 * recent run history.
 *
 * <pre>
 *   GET  /api/v1/super-admin/load-test/status   → running run, capacity, history
 *   POST /api/v1/super-admin/load-test/run      → start a staircase test
 *   POST /api/v1/super-admin/load-test/cancel   → stop the current test
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/super-admin/load-test")
public class LoadTestController {

    private final LoadTestService loadTestService;

    public LoadTestController(LoadTestService loadTestService) {
        this.loadTestService = loadTestService;
    }

    @GetMapping("/status")
    public LoadTestModels.StatusResponse status() {
        return loadTestService.status();
    }

    @PostMapping("/run")
    public ResponseEntity<LoadTestModels.StartResponse> run(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) LoadTestModels.RunRequest body
    ) {
        String callerToken = null;
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            callerToken = authorization.substring(7).trim();
        }
        LoadTestModels.StartResponse started = loadTestService.start(body, callerToken);
        return ResponseEntity.accepted().body(started);
    }

    @PostMapping("/cancel")
    public ResponseEntity<Map<String, Boolean>> cancel() {
        loadTestService.cancel();
        return ResponseEntity.accepted().body(Map.of("cancelled", true));
    }
}
