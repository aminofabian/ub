package zelisline.ub.platform.web;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.ConstraintViolationException;

/**
 * Centralised Problem+JSON ({@link ProblemDetail}) translation for Phase 1.
 *
 * <p>Phase 0's error taxonomy ADR is the source of truth; this handler is the
 * runtime expression of it. Every public endpoint surfaces failures here so the
 * Admin UI can rely on a single shape.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String PROBLEM_BASE = "urn:problem:";

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        body.setTitle("Forbidden");
        body.setType(URI.create(PROBLEM_BASE + "permission-denied"));
        body.setDetail(ex.getMessage() != null ? ex.getMessage() : "Permission denied");
        return problem(body, HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException ex) {
        HttpStatusCode status = ex.getStatusCode();
        ProblemDetail body = ProblemDetail.forStatus(status);
        body.setTitle(reasonOrDefault(status));
        body.setDetail(ex.getReason());
        body.setType(URI.create(PROBLEM_BASE + slug(reasonOrDefault(status))));
        return problem(body, status);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        body.setTitle("Validation failed");
        body.setType(URI.create(PROBLEM_BASE + "validation"));

        List<Map<String, Object>> errors = ex.getBindingResult().getFieldErrors().stream()
                .<Map<String, Object>>map(fe -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("field", fe.getField());
                    entry.put("message", fe.getDefaultMessage());
                    return entry;
                })
                .toList();
        body.setProperty("errors", errors);
        return problem(body, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraint(ConstraintViolationException ex) {
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        body.setTitle("Validation failed");
        body.setType(URI.create(PROBLEM_BASE + "validation"));
        body.setDetail(ex.getMessage());
        return problem(body, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        body.setTitle("Conflict");
        body.setType(URI.create(PROBLEM_BASE + "optimistic-lock"));
        body.setDetail("The resource was modified concurrently; retry with fresh data.");
        return problem(body, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex) {
        String m = String.valueOf(ex.getMostSpecificCause().getMessage()) + " " + ex.getMessage();
        if (m.contains("uq_items_business_sku") || m.toLowerCase().contains("business_sku")) {
            ProblemDetail body = ProblemDetail.forStatus(HttpStatus.CONFLICT);
            body.setTitle("Conflict");
            body.setType(URI.create(PROBLEM_BASE + "duplicate-sku"));
            body.setDetail("SKU already in use");
            return problem(body, HttpStatus.CONFLICT);
        }
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        body.setTitle("Invalid data");
        body.setType(URI.create(PROBLEM_BASE + "data-integrity"));
        body.setDetail("Could not persist the requested change");
        return problem(body, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        body.setTitle("Internal server error");
        body.setType(URI.create(PROBLEM_BASE + "internal-error"));
        return problem(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<ProblemDetail> problem(ProblemDetail body, HttpStatusCode status) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    private String reasonOrDefault(HttpStatusCode status) {
        if (status instanceof HttpStatus s) {
            return s.getReasonPhrase();
        }
        return "Error";
    }

    private String slug(String reason) {
        return reason.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
