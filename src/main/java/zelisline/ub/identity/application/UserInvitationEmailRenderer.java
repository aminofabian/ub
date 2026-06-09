package zelisline.ub.identity.application;

import org.springframework.stereotype.Component;

/**
 * Plain-text invitation email for admin-created staff accounts. The link points
 * at the same set-password page used by password reset, so the invitee chooses
 * their own password and is never sent a credential in the clear.
 */
@Component
public class UserInvitationEmailRenderer {

    public String renderBody(String recipientName, String recipientEmail, String inviteLink, long ttlHours) {
        String greetingName = (recipientName == null || recipientName.isBlank())
                ? recipientEmail
                : recipientName.trim();
        String validity = ttlHours % 24 == 0
                ? (ttlHours / 24) + (ttlHours / 24 == 1 ? " day" : " days")
                : ttlHours + (ttlHours == 1 ? " hour" : " hours");
        return """
                You're invited to UB

                Hi %s,

                An account has been created for you (%s). To get started, choose
                your password using the link below (valid for %s):

                %s

                After setting your password you can sign in with your email and
                that password. If you weren't expecting this, you can ignore this
                message.
                """.formatted(greetingName, recipientEmail, validity, inviteLink).strip();
    }
}
