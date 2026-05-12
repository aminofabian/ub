package zelisline.ub.notifications.api;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;

@RestController
@RequestMapping("/api/v1/me/notification-preferences")
@RequiredArgsConstructor
public class NotificationPreferencesController {

    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> getPreferences(HttpServletRequest request) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        User user = userRepository.findById(principal.userId()).orElseThrow();
        Map<String, Object> prefs = parseSettings(user.getSettings());
        return ResponseEntity.ok(prefs);
    }

    @PutMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> updatePreferences(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        User user = userRepository.findById(principal.userId()).orElseThrow();
        try {
            user.setSettings(objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize settings", e);
        }
        userRepository.save(user);
        return ResponseEntity.ok(body);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSettings(String json) {
        if (json == null || json.isBlank()) {
            return Map.of(
                    "quietHoursEnabled", false,
                    "quietHoursStart", "22:00",
                    "quietHoursEnd", "07:00",
                    "mutedTypes", java.util.Collections.emptyList()
            );
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
