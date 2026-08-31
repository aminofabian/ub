package zelisline.ub.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import zelisline.ub.identity.domain.UserSession;
import zelisline.ub.identity.repository.UserSessionRepository;

@ExtendWith(MockitoExtension.class)
class UserSessionActivityTest {

    @Mock
    private UserSessionRepository userSessionRepository;

    private UserSessionActivity activity;

    @BeforeEach
    void setUp() {
        activity = new UserSessionActivity(userSessionRepository, 24);
    }

    @Test
    void liveJtiReturnsActiveRow() {
        UserSession live = session("jti-live", null, null);
        given(userSessionRepository.findByAccessTokenJtiAndRevokedAtIsNull("jti-live"))
                .willReturn(Optional.of(live));

        assertThat(activity.findLiveSessionForAccessJti("jti-live")).contains(live);
    }

    @Test
    void rotatedPredecessorFollowsToLiveSuccessor() {
        UserSession old = session("jti-old", Instant.now(), "sess-new");
        UserSession neu = session("jti-new", null, null);
        neu.setId("sess-new");
        given(userSessionRepository.findByAccessTokenJtiAndRevokedAtIsNull("jti-old"))
                .willReturn(Optional.empty());
        given(userSessionRepository.findByAccessTokenJti("jti-old")).willReturn(Optional.of(old));
        given(userSessionRepository.findById("sess-new")).willReturn(Optional.of(neu));

        assertThat(activity.findLiveSessionForAccessJti("jti-old")).contains(neu);
    }

    @Test
    void logoutWithoutRotationRejects() {
        UserSession loggedOut = session("jti-out", Instant.now(), null);
        given(userSessionRepository.findByAccessTokenJtiAndRevokedAtIsNull("jti-out"))
                .willReturn(Optional.empty());
        given(userSessionRepository.findByAccessTokenJti("jti-out"))
                .willReturn(Optional.of(loggedOut));

        assertThat(activity.findLiveSessionForAccessJti("jti-out")).isEmpty();
    }

    private static UserSession session(String jti, Instant revokedAt, String rotatedTo) {
        UserSession row = new UserSession();
        row.setId("sess-" + jti);
        row.setAccessTokenJti(jti);
        row.setRevokedAt(revokedAt);
        row.setRotatedToId(rotatedTo);
        row.setLastSeenAt(Instant.now());
        return row;
    }
}
