package zelisline.ub.notifications.infrastructure;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExpoPushSenderTest {

    @Test
    void recognisesExpoTokensAndIgnoresFcm() {
        assertTrue(ExpoPushSender.isExpoPushToken("ExponentPushToken[abc]"));
        assertTrue(ExpoPushSender.isExpoPushToken("ExpoPushToken[xyz]"));
        assertFalse(ExpoPushSender.isExpoPushToken("dGhpLWlzLW5vdC1leHBv"));
        assertFalse(ExpoPushSender.isExpoPushToken(""));
        assertFalse(ExpoPushSender.isExpoPushToken(null));
    }
}
