package zelisline.ub.identity.application;

import org.springframework.stereotype.Component;

@Component
public class EmailVerificationEmailRenderer {

    public String renderBody(String recipientEmail, String verifyLink) {
        return """
                UB — verify your email

                We received a registration for %s.

                Open this link to activate your account:
                %s

                If you did not sign up, you can ignore this message.
                """.formatted(recipientEmail, verifyLink).strip();
    }
}
