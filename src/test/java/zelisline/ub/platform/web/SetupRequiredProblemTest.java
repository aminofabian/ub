package zelisline.ub.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;

/**
 * The wire contract the "set this up first" dialogs depend on: a tagged problem document that
 * still carries the old status and text, so older clients that match on the message keep working.
 */
class SetupRequiredProblemTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(
            mock(AuditEventPublisher.class), new AuditEventBuilder(new ObjectMapper()));

    @Test
    void shiftIsTaggedAndKeepsStatusAndText() {
        ResponseEntity<ProblemDetail> res = handler.handleSetupRequired(
                new SetupRequiredException(HttpStatus.CONFLICT, SetupRequirement.SHIFT, "No open shift"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        ProblemDetail body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getType()).isEqualTo(URI.create("urn:problem:setup-required"));
        assertThat(body.getTitle()).isEqualTo("No open shift");
        assertThat(body.getDetail()).isEqualTo("No open shift");
        assertThat(body.getProperties())
                .containsEntry("code", "setup_required")
                .containsEntry("requirement", "shift")
                .doesNotContainKey("hint");
    }

    @Test
    void tillIsTagged() {
        ResponseEntity<ProblemDetail> res = handler.handleSetupRequired(
                new SetupRequiredException(HttpStatus.FORBIDDEN, SetupRequirement.TILL, "Register this till"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody().getProperties()).containsEntry("requirement", "till");
    }

    @Test
    void branchCarriesItsHint() {
        ResponseEntity<ProblemDetail> none = handler.handleSetupRequired(new SetupRequiredException(
                HttpStatus.BAD_REQUEST, SetupRequirement.BRANCH, SetupRequiredException.HINT_NONE, "Add a branch"));
        ResponseEntity<ProblemDetail> assign = handler.handleSetupRequired(new SetupRequiredException(
                HttpStatus.BAD_REQUEST, SetupRequirement.BRANCH, SetupRequiredException.HINT_ASSIGN, "Ask an owner"));

        assertThat(none.getBody().getProperties())
                .containsEntry("requirement", "branch")
                .containsEntry("hint", "none");
        assertThat(assign.getBody().getProperties()).containsEntry("hint", "assign");
    }

    @Test
    void stillAResponseStatusExceptionSoExistingCatchersKeepWorking() {
        SetupRequiredException ex =
                new SetupRequiredException(HttpStatus.CONFLICT, SetupRequirement.SHIFT, "No open shift");

        assertThat(ex).isInstanceOf(ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getReason()).isEqualTo("No open shift");
    }

    @Test
    void requirementCodesAreTheStableWireValues() {
        assertThat(SetupRequirement.BRANCH.code()).isEqualTo("branch");
        assertThat(SetupRequirement.SHIFT.code()).isEqualTo("shift");
        assertThat(SetupRequirement.TILL.code()).isEqualTo("till");
    }
}
