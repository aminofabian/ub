package zelisline.ub.platform.api;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mobile SQLite cache schema versioning for native apps.
 *
 * <p>GET /api/v1/platform/mobile-schema-version
 */
@RestController
@RequestMapping("/api/v1/platform")
public class MobileSchemaVersionController {

    private static final int SCHEMA_VERSION = 1;
    private static final String MIN_APP_VERSION = "0.0.1";

    @GetMapping("/mobile-schema-version")
    public ResponseEntity<Map<String, Object>> mobileSchemaVersion() {
        return ResponseEntity.ok(Map.of(
                "schemaVersion", SCHEMA_VERSION,
                "minAppVersion", MIN_APP_VERSION,
                "breakingChangeAt", Instant.parse("2099-01-01T00:00:00Z").toString()
        ));
    }
}
