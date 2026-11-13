package zelisline.ub.posdraft.application;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A ticket's prices were brought up to date with the catalogue instead of being paid. The
 * transaction that throws this is committed (see {@code noRollbackFor}), so the ticket keeps its
 * new total and the till can take payment again.
 */
public class TicketRepricedException extends ResponseStatusException {

    public TicketRepricedException(String reason) {
        super(HttpStatus.CONFLICT, reason);
    }
}
