package zelisline.ub.desktop.application;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.api.dto.LoginRequest;
import zelisline.ub.identity.api.dto.LoginResponse;

/**
 * Desktop only: checks a staff email + password against the online shop.
 *
 * <p>A till connected by a cashier receives the staff list without credential
 * hashes (the cloud only hands those to owner/admin/manager sessions). Such a
 * staff member can still sign in on the till the first time while it is
 * online: the till asks the online shop to verify the password, and on success
 * {@code AuthService} stores a local hash so later sign-ins work offline.
 *
 * <p>Returns false on any failure (offline, wrong password, no cloud link) —
 * the caller then answers with the normal "invalid credentials".
 */
@Service
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopCloudCredentialVerifier {

    private static final Logger log = LoggerFactory.getLogger(DesktopCloudCredentialVerifier.class);

    private final CloudSyncSession cloudSyncSession;
    private final RestClient.Builder restClientBuilder;

    public boolean verify(String email, String password) {
        if (email == null || email.isBlank() || password == null || password.isEmpty()) {
            return false;
        }
        Optional<CloudSyncSession.Session> session = cloudSyncSession.load();
        if (session.isEmpty()) {
            return false;
        }
        CloudSyncSession.Session s = session.get();
        try {
            LoginResponse resp = restClientBuilder
                .baseUrl(s.origin())
                .build()
                .post()
                .uri("/api/v1/auth/login")
                .header("X-Tenant-Id", s.cloudBusinessId())
                .header("X-Kiosk-Client", "native")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new LoginRequest(email, password))
                .retrieve()
                .body(LoginResponse.class);
            return resp != null && resp.accessToken() != null && !resp.accessToken().isBlank();
        } catch (Exception e) {
            log.debug("[DesktopAuth] online verification failed for a staff sign-in: {}", e.getMessage());
            return false;
        }
    }
}
