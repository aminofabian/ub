package zelisline.ub.platform.web;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

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

import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.messaging.application.SmsCreditsDepletedException;
import zelisline.ub.platform.logs.PlatformRequestLogErrorCapture;
import zelisline.ub.platform.persistence.DataIntegrityProblems;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Centralised Problem+JSON ({@link ProblemDetail}) translation for Phase 1.
 *
 * <p>Phase 0's error taxonomy ADR is the source of truth; this handler is the
 * runtime expression of it. Every public endpoint surfaces failures here so the
 * Admin UI can rely on a single shape.
 */
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String PROBLEM_BASE = "urn:problem:";

    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

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

    /** SMS balance at zero — HTTP 402 with the fields the header chip needs. */
    @ExceptionHandler(SmsCreditsDepletedException.class)
    public ResponseEntity<ProblemDetail> handleSmsCreditsDepleted(SmsCreditsDepletedException ex) {
        log.warn("SMS credits depleted: {}", ex.getMessage());
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.PAYMENT_REQUIRED);
        body.setTitle("SMS credits depleted");
        body.setDetail(ex.getMessage());
        body.setType(URI.create(PROBLEM_BASE + "sms-credits-depleted"));
        body.setProperty("available", ex.getAvailable());
        body.setProperty("includedRemaining", ex.getIncludedRemaining());
        body.setProperty("purchasedBalance", ex.getPurchasedBalance());
        body.setProperty("unitPriceKes", ex.getUnitPriceKes());
        return problem(body, HttpStatus.PAYMENT_REQUIRED);
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
        String flat = flattenMessages(ex).toLowerCase();
        if (flat.contains("supplier_users") && (flat.contains("phone") || flat.contains("uq_supplier_users_phone"))) {
            ProblemDetail body = ProblemDetail.forStatus(HttpStatus.CONFLICT);
            body.setTitle("Conflict");
            body.setType(URI.create(PROBLEM_BASE + "duplicate-supplier-phone"));
            body.setDetail("This phone already has an account — sign in");
            return problem(body, HttpStatus.CONFLICT);
        }
        if (flat.contains("supplier_users") && (flat.contains("email") || flat.contains("uq_supplier_users_email"))) {
            ProblemDetail body = ProblemDetail.forStatus(HttpStatus.CONFLICT);
            body.setTitle("Conflict");
            body.setType(URI.create(PROBLEM_BASE + "duplicate-supplier-email"));
            body.setDetail("This email already has an account — sign in");
            return problem(body, HttpStatus.CONFLICT);
        }
        if (flat.contains("uq_marketplace_suppliers_username")
                || (flat.contains("marketplace_suppliers") && flat.contains("username"))) {
            ProblemDetail body = ProblemDetail.forStatus(HttpStatus.CONFLICT);
            body.setTitle("Conflict");
            body.setType(URI.create(PROBLEM_BASE + "duplicate-supplier-username"));
            body.setDetail("Username is taken");
            return problem(body, HttpStatus.CONFLICT);
        }
        if (flat.contains("uq_supplier_invoices_business_no") || flat.contains("invoice_number")) {
            ProblemDetail body = ProblemDetail.forStatus(HttpStatus.CONFLICT);
            body.setTitle("Conflict");
            body.setType(URI.create(PROBLEM_BASE + "duplicate-invoice"));
            body.setDetail(
                    "A supplier invoice for this receipt already exists. Refresh supplies and retry if stock was not updated.");
            return problem(body, HttpStatus.CONFLICT);
        }
        if (flat.contains("fk_si_rps") || flat.contains("raw_purchase_session")) {
            ProblemDetail body = ProblemDetail.forStatus(HttpStatus.CONFLICT);
            body.setTitle("Conflict");
            body.setType(URI.create(PROBLEM_BASE + "supply-session-linked"));
            body.setDetail(
                    "This supply is still linked to a purchase receipt used by another invoice. Refresh and retry, or delete the duplicate invoice first.");
            return problem(body, HttpStatus.CONFLICT);
        }
        if (flat.contains("fk_rpl_inventory_batch") || flat.contains("inventory_batch_id")) {
            ProblemDetail body = ProblemDetail.forStatus(HttpStatus.CONFLICT);
            body.setTitle("Conflict");
            body.setType(URI.create(PROBLEM_BASE + "supply-batch-linked"));
            body.setDetail(
                    "Stock batches from this supply are still linked. Refresh and retry delete.");
            return problem(body, HttpStatus.CONFLICT);
        }
        if (flat.contains("fk_spa_invoice") || flat.contains("supplier_payment_allocations")) {
            ProblemDetail body = ProblemDetail.forStatus(HttpStatus.CONFLICT);
            body.setTitle("Conflict");
            body.setType(URI.create(PROBLEM_BASE + "supply-has-payments"));
            body.setDetail("Remove payments from this invoice before deleting it");
            return problem(body, HttpStatus.CONFLICT);
        }
        if (flat.contains("fk_ib_supply_batch") || flat.contains("supply_batch_id")) {
            ProblemDetail body = ProblemDetail.forStatus(HttpStatus.CONFLICT);
            body.setTitle("Conflict");
            body.setType(URI.create(PROBLEM_BASE + "supply-batch-linked"));
            body.setDetail(
                    "Inventory rows still reference this supply batch. Refresh and retry delete.");
            return problem(body, HttpStatus.CONFLICT);
        }
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        body.setTitle("Invalid data");
        body.setType(URI.create(PROBLEM_BASE + "data-integrity"));
        body.setDetail("Could not persist the requested change");
        return problem(body, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler({
            org.springframework.transaction.UnexpectedRollbackException.class,
            org.springframework.transaction.TransactionSystemException.class
    })
    public ResponseEntity<ProblemDetail> handleTransactionFailure(Exception ex, HttpServletRequest request) {
        String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        log.error("Transaction failure (correlationId={})", correlationId, ex);
        publishSystemException(request, ex);
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        body.setTitle("Database not ready");
        body.setType(URI.create(PROBLEM_BASE + "schema-mismatch"));
        body.setDetail(schemaMismatchDetail(ex));
        PlatformRequestLogErrorCapture.capture(request, body, ex);
        return problem(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Unknown paths must be 404, not 500. Without this, a stale cached UI
     * (WebView2 cache) calling a route the jar doesn't have — or a typo'd
     * path — surfaces as "Unexpected server error" toasts that look like a
     * broken backend and send the user hunting for a non-existent outage.
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResource(
            org.springframework.web.servlet.resource.NoResourceFoundException ex,
            HttpServletRequest request) {
        String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        log.warn("Client error 404: {} (correlationId={})", ex.getMessage(), correlationId);
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        body.setTitle("Not found");
        body.setType(URI.create(PROBLEM_BASE + "not-found"));
        body.setDetail("This endpoint does not exist on this install. "
                + "Update the app so the UI and backend match.");
        return problem(body, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        // Nested UnexpectedRollbackException under a different wrapper.
        if (ex.getCause() instanceof org.springframework.transaction.UnexpectedRollbackException
                || ex.getCause() instanceof org.springframework.transaction.TransactionSystemException) {
            return handleTransactionFailure(ex, request);
        }
        String flat = flattenMessages(ex).toLowerCase();
        if (flat.contains("supplier_user_sessions")
                || flat.contains("supplier_phone_verifications")
                || flat.contains("supplier_users")
                || flat.contains("marketplace_suppliers")) {
            log.error("Supplier portal persistence failure (correlationId={})", correlationId, ex);
            publishSystemException(request, ex);
            ProblemDetail body = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
            body.setTitle("Database not ready");
            body.setType(URI.create(PROBLEM_BASE + "schema-mismatch"));
            body.setDetail(schemaMismatchDetail(ex));
            PlatformRequestLogErrorCapture.capture(request, body, ex);
            return problem(body, HttpStatus.INTERNAL_SERVER_ERROR);
        }
        log.error("Unhandled exception (correlationId={})", correlationId, ex);
        publishSystemException(request, ex);
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        body.setTitle("Internal server error");
        body.setType(URI.create(PROBLEM_BASE + "internal-error"));
        body.setDetail("Unexpected server error. Retry, or sign in if your account was already created.");
        PlatformRequestLogErrorCapture.capture(request, body, ex);
        return problem(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Records an ERROR-severity {@code system.exception} audit event for tenant-scoped
     * 5xx responses. Platform-level / pre-tenant requests (no resolvable business id)
     * are skipped, and an audit-write failure never masks the original response.
     */
    private void publishSystemException(HttpServletRequest request, Exception ex) {
        try {
            String businessId = TenantRequestIds.resolveBusinessId(request);
            if (businessId == null || businessId.isBlank()) {
                return;
            }
            String actorId = safeAuditActorId(request);
            String message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            auditEventPublisher.publishSynchronous(auditEventBuilder
                    .builder(AuditEventCategory.SYSTEM, AuditEventTypes.SYSTEM_EXCEPTION, AuditEventSeverity.ERROR)
                    .businessId(businessId)
                    .actor(actorId, actorId != null ? AuditEventActorType.USER : AuditEventActorType.SYSTEM)
                    .target("request", null)
                    .targetLabel(request.getMethod() + " " + request.getRequestURI())
                    .source("api")
                    .reason(truncate(message, 500))
                    .metadata(Map.of(
                            "exception", ex.getClass().getName(),
                            "method", request.getMethod(),
                            "path", request.getRequestURI()
                    )).build());
        } catch (Exception ignored) {
            // Never fail the response because of an audit write.
        }
    }

    private static String safeAuditActorId(HttpServletRequest request) {
        try {
            return CurrentTenantUser.auditActorId(request);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private ResponseEntity<ProblemDetail> problem(ProblemDetail body, HttpStatusCode status) {
        PlatformRequestLogErrorCapture.capture(body, null);
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
        if (message.contains("supplier_user_sessions")) {
            return "Supplier portal sessions table is missing. Redeploy the API so Flyway can apply migration V172__supplier_portal_sessions_notifications.sql.";
        }
        if (message.contains("supplier_phone_verifications")
                || message.contains("platform_supplier_portal")
                || message.contains("supplier_portal_claim")) {
            return "Supplier Portal claim tables are missing. Redeploy the API so Flyway can apply migrations V169–V172.";
        }
        if (message.contains("supplier_users") || message.contains("marketplace_suppliers")) {
            return "Supplier Portal identity tables are incomplete. Redeploy the API so Flyway can apply migrations V136/V168/V169.";
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
