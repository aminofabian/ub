package zelisline.ub.identity.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import zelisline.ub.identity.application.NotificationService;

@Service
public class LoggingNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationService.class);

    @Override
    public void sendPasswordResetEmail(String toEmail, String subject, String textBody) {
        log.info("[notification] password reset to={} subject={}\n{}", toEmail, subject, textBody);
    }

    @Override
    public void sendTemporaryLockNotice(String toEmail) {
        log.info("[notification] account temporarily locked to={}", toEmail);
    }
}
