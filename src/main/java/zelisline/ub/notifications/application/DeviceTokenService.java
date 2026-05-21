package zelisline.ub.notifications.application;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.notifications.api.dto.RegisterDeviceTokenRequest;
import zelisline.ub.notifications.api.dto.RegisterFcmTokenRequest;
import zelisline.ub.notifications.domain.DeviceToken;
import zelisline.ub.notifications.repository.DeviceTokenRepository;

@Service
@RequiredArgsConstructor
public class DeviceTokenService {

    private final DeviceTokenRepository deviceTokenRepository;

    @Transactional
    public DeviceToken register(String businessId, String userId, RegisterDeviceTokenRequest req, String userAgent) {
        String endpoint = req.endpoint().trim();
        if (endpoint.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endpoint required");
        }
        DeviceToken token = deviceTokenRepository.findByEndpointAndRevokedAtIsNull(endpoint).orElse(new DeviceToken());
        token.setBusinessId(businessId);
        token.setUserId(userId);
        token.setPlatform(req.platform() != null ? req.platform().trim() : DeviceToken.PLATFORM_WEB);
        token.setEndpoint(endpoint);
        token.setToken(endpoint);
        token.setP256dh(req.p256dh().trim());
        token.setAuthSecret(req.auth().trim());
        token.setUserAgent(userAgent != null && userAgent.length() > 512 ? userAgent.substring(0, 512) : userAgent);
        token.setRevokedAt(null);
        token.setLastSeenAt(Instant.now());
        return deviceTokenRepository.save(token);
    }

    private static String normalizeMobilePlatform(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "platform required");
        }
        String p = raw.trim().toUpperCase();
        if (!DeviceToken.PLATFORM_ANDROID.equals(p) && !DeviceToken.PLATFORM_IOS.equals(p)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "platform must be ANDROID or IOS");
        }
        return p;
    }

    @Transactional
    public DeviceToken registerFcm(
            String businessId,
            String userId,
            RegisterFcmTokenRequest req,
            String userAgent
    ) {
        String platform = normalizeMobilePlatform(req.platform());
        String fcmToken = req.token().trim();
        if (fcmToken.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "token required");
        }
        DeviceToken row = deviceTokenRepository
                .findByBusinessIdAndUserIdAndPlatformAndTokenAndRevokedAtIsNull(
                        businessId, userId, platform, fcmToken)
                .orElse(new DeviceToken());
        row.setBusinessId(businessId);
        row.setUserId(userId);
        row.setPlatform(platform);
        row.setToken(fcmToken);
        row.setEndpoint(null);
        row.setP256dh(null);
        row.setAuthSecret(null);
        row.setUserAgent(userAgent != null && userAgent.length() > 512 ? userAgent.substring(0, 512) : userAgent);
        row.setRevokedAt(null);
        row.setLastSeenAt(Instant.now());
        return deviceTokenRepository.save(row);
    }

    @Transactional
    public void revoke(String businessId, String userId, String tokenId) {
        int updated = deviceTokenRepository.revoke(tokenId, businessId, userId, Instant.now());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Device token not found");
        }
    }
}
