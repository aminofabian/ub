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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.PersistenceException;
import jakarta.validation.ConstraintViolationException;

import zelisline.ub.platform.persistence.DataIntegrityProblems;

/**
 * Centralised Problem+JSON ({@link ProblemDetail}) translation for Phase 1.
 *
 * <p>Phase 0's error taxonomy ADR is the source of truth; this handler is the
 * runtime expression of it. Every public endpoint surfaces failures here so the
 * Admin UI can rely on a single shape.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String PROBLEM_BASE = "urn:problem:";

    @ExceptionHandler(InvalidDataAccessResourceUsageException.class)
    public ResponseEntity<ProblemDetail> handleInvalidDataAccess(InvalidDataAccessResourceUsageException ex) {
        log.error("Database schema/query error", ex);
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        body.setTitle("Database not ready");
        body.setType(URI.create(PROBLEM_BASE + "schema-mismatch"));
        body.setDetail(schemaMismatchDetail(ex));
        return problem(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(BadSqlGrammarException.class)
    public ResponseEntity<ProblemDetail> handleBadSqlGrammar(BadSqlGrammarException ex) {
        log.error("SQL grammar error", ex);
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        body.setTitle("Database not ready");
        body.setType(URI.create(PROBLEM_BASE + "schema-mismatch"));
        body.setDetail(schemaMismatchDetail(ex));
        return problem(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(PersistenceException.class)
    public ResponseEntity<ProblemDetail> handlePersistence(PersistenceException ex) {
        log.error("Persistence error", ex);
        String detail = schemaMismatchDetail(ex);
        if (detail.toLowerCase().contains("path b draft")
                || detail.toLowerCase().contains("migration")) {
            ProblemDetail body = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
            body.setTitle("Database not ready");
            body.setType(URI.create(PROBLEM_BASE + "schema-mismatch"));
            body.setDetail(detail);
            return problem(body, HttpStatus.INTERNAL_SERVER_ERROR);
        }
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        body.setTitle("Internal server error");
        body.setType(URI.create(PROBLEM_BASE + "internal-error"));
        return problem(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(IncorrectResultSizeDataAccessException.class)
    public ResponseEntity<ProblemDetail> handleIncorrectResultSize(IncorrectResultSizeDataAccessException ex) {
        log.warn("Non-unique query result: {}", ex.getMessage());
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        body.setTitle("Conflict");
        body.setType(URI.create(PROBLEM_BASE + "non-unique-result"));
        body.setDetail("Multiple matching records were found where one was expected. Retry the request.");
        return problem(body, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        body.setTitle("Forbidden");
        body.setType(URI.create(PROBLEM_BASE + "permission-denied"));
        body.setDetail(ex.getMessage() != null ? ex.getMessage() : "Permission denied");
        return problem(body, HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException ex) {
        HttpStatusCode status = ex.getStatusCode();
        if (status.is4xxClientError()) {
            log.warn("Client error {}: {}", status.value(), ex.getReason());
        } else {
            log.error("Server error {}: {}", status.value(), ex.getReason());
        }
        ProblemDetail body = ProblemDetail.forStatus(status);
        String reason = ex.getReason();
        if (reason != null && !reason.isBlank()) {
            body.setTitle(reason.trim());
        } else {
            body.setTitle(reasonOrDefault(status));
        }
        body.setDetail(reason);
        body.setType(URI.create(PROBLEM_BASE + slug(reasonOrDefault(status))));
        return problem(body, status);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        log.warn("Validation failed: {}", ex.getMessage());
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
        log.warn("Constraint violation: {}", ex.getMessage());
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        body.setTitle("Validation failed");
        body.setType(URI.create(PROBLEM_BASE + "validation"));
        body.setDetail(ex.getMessage());
        return problem(body, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        log.warn("Optimistic locking conflict: {}", ex.getMessage());
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        body.setTitle("Conflict");
        body.setType(URI.create(PROBLEM_BASE + "optimistic-lock"));
        body.setDetail("The resource was modified concurrently; retry with fresh data.");
        return problem(body, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMessage());
        if (DataIntegrityProblems.isDuplicateSku(ex)) {
            ProblemDetail body = ProblemDetail.forStatus(HttpStatus.CONFLICT);
            body.setTitle("Conflict");
            body.setType(URI.create(PROBLEM_BASE + "duplicate-sku"));
            body.setDetail("SKU already in use");
            return problem(body, HttpStatus.CONFLICT);
        }
        if (DataIntegrityProblems.isDuplicateCustomerPhone(ex)) {
            ProblemDetail body = ProblemDetail.forStatus(HttpStatus.CONFLICT);
            body.setTitle("Conflict");
            body.setType(URI.create(PROBLEM_BASE + "duplicate-customer-phone"));
            body.setDetail("Phone already in use for this business");
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
        String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        log.error("Unhandled exception (correlationId={})", correlationId, ex);
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

    private static String schemaMismatchDetail(Throwable ex) {
        String message = flattenMessages(ex).toLowerCase();
        if (message.contains("stock_take_restock_items")) {
            return "Restock tables are missing. Redeploy the API so Flyway can apply migration V134__stock_take_restock_items.sql.";
        }
        if (message.contains("daily_stock_audit")) {
            return "Daily audit tables are missing. Redeploy the API so Flyway can apply migration V133__daily_stock_audit.sql.";
        }
        if (message.contains("client_draft_json")
                || message.contains("draft_qty")
                || message.contains("draft_unit_cost")
                || message.contains("draft_sell_price")
                || message.contains("draft_expiry_date")) {
            return "Path B draft columns are missing. Redeploy the API so Flyway can apply migrations V154/V155 (path_b draft fields).";
        }
        return "A required database migration may be missing. Redeploy the API so Flyway can run pending migrations.";
    }

    private static String flattenMessages(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = ex;
        int depth = 0;
        while (cur != null && depth < 8) {
            if (cur.getMessage() != null) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(cur.getMessage());
            }
            cur = cur.getCause();
            depth++;
        }
        return sb.toString();
    }
}
