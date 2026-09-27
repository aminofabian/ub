package zelisline.ub.integrations.pickupmtaani.infrastructure;

/**
 * A failed Pickup Mtaani API call, already normalised from the several error
 * envelopes the 0.1.0 spec returns ({@code {message, validationErrors[]}},
 * {@code {success, message}}, and the newer {@code ErrorResponse} with
 * {@code code}, {@code display.message}, {@code request_id}).
 *
 * <p>{@link #getMessage()} is safe to show a merchant. {@link #getCode()} and
 * {@link #getRequestId()} are for logs and support, never for the shopper.
 */
public class PickupMtaaniApiException extends RuntimeException {

    private final Integer httpStatus;
    private final String code;
    private final String requestId;

    public PickupMtaaniApiException(Integer httpStatus, String code, String requestId, String safeMessage) {
        super(safeMessage);
        this.httpStatus = httpStatus;
        this.code = code;
        this.requestId = requestId;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getRequestId() {
        return requestId;
    }

    /** True for auth failures, which will not improve by retrying. */
    public boolean authFailure() {
        return httpStatus != null && (httpStatus == 401 || httpStatus == 403);
    }
}
