package zelisline.ub.identity.application;

import org.springframework.stereotype.Component;

/**
 * Plain-text password reset email (golden-file tested, PHASE_1_PLAN.md §3.6).
 */
@Component
public class PasswordResetEmailRenderer {

    public String renderBody(String recipientEmail, String resetLink) {
        return """
                UB password reset

                We received a request to reset the password for %s.

                Open this link to choose a new password (valid for one hour):
                %s

                If you did not request this, you can ignore this message.
                """.formatted(recipientEmail, resetLink).strip();
    }
}
