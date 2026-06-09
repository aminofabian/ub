package zelisline.ub.identity.application;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.domain.PasswordResetToken;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.PasswordResetTokenRepository;
import zelisline.ub.identity.repository.UserRepository;

/**
 * Issues an invitation for an admin-created staff account: mints a single-use
 * set-password token (reusing the password-reset token store and the
 * {@code /reset-password} page) and emails the invitee a link. The user never
 * receives a credential in the clear — they choose their own password.
 */
@Service
@RequiredArgsConstructor
public class UserInvitationService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final NotificationService notificationService;
    private final FrontendAuthLinkBuilder frontendAuthLinkBuilder;
    private final UserInvitationEmailRenderer invitationEmailRenderer;

    @Value("${app.auth.invite-ttl-hours:168}")
    private long inviteTtlHours;

    @Transactional
    public void sendInvite(HttpServletRequest http, String businessId, String userId) {
        User user = userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId).orElse(null);
        if (user == null) {
            return;
        }
        String raw = newSecureToken();
        PasswordResetToken token = new PasswordResetToken();
        token.setUserId(user.getId());
        token.setTokenHash(TokenHasher.sha256Hex(raw));
        token.setExpiresAt(Instant.now().plus(inviteTtlHours, ChronoUnit.HOURS));
        passwordResetTokenRepository.save(token);

        String link = frontendAuthLinkBuilder.passwordResetLink(http, businessId, raw);
        String subject = "You're invited — set your password";
        String body = invitationEmailRenderer.renderBody(user.getName(), user.getEmail(), link, inviteTtlHours);
        notificationService.sendPasswordResetEmail(user.getEmail(), subject, body);
    }

    private static String newSecureToken() {
        byte[] rnd = new byte[32];
        new SecureRandom().nextBytes(rnd);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(rnd);
    }
}
