package zelisline.ub.finance.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.finance.FinanceConstants;
import zelisline.ub.finance.api.dto.ExpenseKopokopoPayResponse;
import zelisline.ub.finance.api.dto.ExpensePayOptionsResponse;
import zelisline.ub.finance.domain.Expense;
import zelisline.ub.finance.domain.ExpenseDisbursement;
import zelisline.ub.finance.domain.ExpenseDisbursementStatuses;
import zelisline.ub.finance.domain.ExpenseSchedule;
import zelisline.ub.finance.domain.ExpenseScheduleOccurrence;
import zelisline.ub.finance.repository.ExpenseDisbursementRepository;
import zelisline.ub.finance.repository.ExpenseRepository;
import zelisline.ub.finance.repository.ExpenseScheduleOccurrenceRepository;
import zelisline.ub.finance.repository.ExpenseScheduleRepository;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.payments.application.SupplierPayoutSettingsService;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.PaymentWebhookEvent;
import zelisline.ub.payments.domain.spi.SendMoneyRequest;
import zelisline.ub.payments.domain.spi.SendMoneyResult;
import zelisline.ub.payments.domain.spi.WebhookResult;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.payments.infrastructure.KopokopoPaymentGateway;
import zelisline.ub.payments.repository.PaymentGatewayConfigRepository;
import zelisline.ub.payments.repository.PaymentWebhookEventRepository;

/**
 * Expense → KopoKopo Send Money (tenant BYO). Auto-post ≠ auto-pay: initiation is
 * always an explicit owner/manager action. On success, sets {@code expenses.paid_at}.
 */
@Service
@RequiredArgsConstructor
public class ExpenseDisbursementService {

    private static final Logger log = LoggerFactory.getLogger(ExpenseDisbursementService.class);
    private static final BigDecimal MONEY = new BigDecimal("0.01");

    private final ExpenseRepository expenseRepository;
    private final ExpenseDisbursementRepository disbursementRepository;
    private final ExpenseScheduleOccurrenceRepository occurrenceRepository;
    private final ExpenseScheduleRepository scheduleRepository;
    private final PaymentGatewayConfigRepository configRepository;
    private final SupplierPayoutSettingsService supplierPayoutSettingsService;
    private final CredentialEncryptionService encryptionService;
    private final KopokopoPaymentGateway kopokopoGateway;
    private final PaymentWebhookEventRepository webhookEventRepository;
    private final ObjectMapper objectMapper;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    @Value("${app.public.api-base-url:http://localhost:5050}")
    private String publicApiBaseUrl;

    @Transactional
    public ExpensePayOptionsResponse payOptions(String businessId, String expenseId) {
        Expense expense = requirePostedExpense(businessId, expenseId);
        Destination dest = resolveDestination(expense);

        Optional<PaymentGatewayConfig> payoutGateway = supplierPayoutSettingsService.resolveActivePayoutConfig(businessId);
        boolean payoutEnabled = supplierPayoutSettingsService.isSupplierPayoutToggleEnabled(businessId);
        boolean gatewayReady = payoutGateway.isPresent();
        boolean destinationConfigured = dest.phone() != null;
        boolean alreadyPaid = expense.getPaidAt() != null
                || hasSuccessfulDisbursement(businessId, expenseId);
        boolean kopokopoEligible = gatewayReady
                && destinationConfigured
                && !alreadyPaid
                && expense.getAmount().compareTo(MONEY) > 0
                && FinanceConstants.EXPENSE_PAY_METHOD_MPESA_MANUAL.equals(expense.getPaymentMethod());

        Optional<ExpenseDisbursement> pending = findPendingDisbursement(businessId, expenseId);
        Optional<ExpenseDisbursement> latest = disbursementRepository
                .findByBusinessIdAndExpenseIdOrderByCreatedAtDesc(businessId, expenseId)
                .stream()
                .findFirst();
        if (latest.isPresent() && isOpenForConfirm(latest.get())) {
            pollSendMoneyStatus(latest.get());
            expense = expenseRepository.findByIdAndBusinessId(expenseId, businessId).orElse(expense);
            alreadyPaid = expense.getPaidAt() != null
                    || ExpenseDisbursementStatuses.SUCCESS.equals(latest.get().getStatus());
            kopokopoEligible = gatewayReady
                    && destinationConfigured
                    && !alreadyPaid
                    && expense.getAmount().compareTo(MONEY) > 0
                    && FinanceConstants.EXPENSE_PAY_METHOD_MPESA_MANUAL.equals(expense.getPaymentMethod());
        }

        return new ExpensePayOptionsResponse(
                expense.getAmount(),
                payoutEnabled,
                gatewayReady,
                payoutGateway.map(PaymentGatewayConfig::getLabel).orElse(null),
                destinationConfigured,
                dest.phone(),
                kopokopoEligible,
                pending.filter(d -> ExpenseDisbursementStatuses.PENDING.equals(d.getStatus())).isPresent()
                        || latest.filter(d -> ExpenseDisbursementStatuses.PENDING.equals(d.getStatus())).isPresent(),
                pending.map(ExpenseDisbursement::getId)
                        .or(() -> latest.filter(d -> ExpenseDisbursementStatuses.PENDING.equals(d.getStatus()))
                                .map(ExpenseDisbursement::getId))
                        .orElse(null),
                latest.map(ExpenseDisbursement::getStatus).orElse(null),
                latest.map(this::publicDisbursementMessage).orElse(null),
                alreadyPaid);
    }

    @Transactional
    public ExpenseKopokopoPayResponse initiate(String businessId, String expenseId, String userId) {
        Expense expense = requirePostedExpense(businessId, expenseId);
        if (!FinanceConstants.EXPENSE_PAY_METHOD_MPESA_MANUAL.equals(expense.getPaymentMethod())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Send Money is only for M-Pesa (manual) expenses — set payment method on the expense or schedule");
        }
        if (expense.getAmount().compareTo(MONEY) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expense amount must be > 0");
        }
        if (expense.getPaidAt() != null || hasSuccessfulDisbursement(businessId, expenseId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Expense is already paid");
        }

        findPendingDisbursement(businessId, expenseId).ifPresent(d -> {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A KopoKopo payment is already pending for this expense");
        });

        Optional<ExpenseDisbursement> latest = disbursementRepository
                .findByBusinessIdAndExpenseIdOrderByCreatedAtDesc(businessId, expenseId)
                .stream()
                .findFirst();
        if (latest.isPresent() && isOpenForConfirm(latest.get())) {
            ExpenseDisbursement prior = latest.get();
            pollSendMoneyStatus(prior);
            if (ExpenseDisbursementStatuses.SUCCESS.equals(prior.getStatus())) {
                return new ExpenseKopokopoPayResponse(
                        true,
                        prior.getId(),
                        prior.getKopokopoSendMoneyId(),
                        prior.getStatus(),
                        "Payment already confirmed with KopoKopo");
            }
            if (ExpenseDisbursementStatuses.PENDING.equals(prior.getStatus())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "A KopoKopo payment is already pending for this expense");
            }
        }

        Destination dest = resolveDestination(expense);
        if (dest.phone() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Add a vendor M-Pesa number on the expense or its recurring schedule");
        }

        PaymentGatewayConfig cfg = supplierPayoutSettingsService.resolveActivePayoutConfig(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Payouts are disabled or no active payout gateway is configured. "
                                + "Enable under Payments → Supplier payouts."));
        if (cfg.getGatewayType() != GatewayType.KOPOKOPO) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Expense payout via " + cfg.getGatewayType().name() + " is not implemented yet");
        }

        Map<String, String> creds = decryptCredentials(cfg);
        String till = creds.getOrDefault("tillNumber", creds.get("shortcode"));

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("expenseId", expense.getId());
        metadata.put("businessId", businessId);
        metadata.put("reference", expense.getId());
        if (dest.occurrenceId() != null) {
            metadata.put("occurrenceId", dest.occurrenceId());
        }
        if (dest.scheduleId() != null) {
            metadata.put("scheduleId", dest.scheduleId());
        }

        SendMoneyRequest request = SendMoneyRequest.toMobileWallet(
                creds,
                publicApiBaseUrl.replaceAll("/+$", ""),
                dest.phone(),
                expense.getAmount(),
                "KES",
                "Expense " + expense.getName(),
                till,
                metadata);

        SendMoneyResult result = kopokopoGateway.sendMoney(request);
        if (!result.accepted() || result.sendMoneyId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    result.message() != null ? result.message() : "KopoKopo Send Money declined");
        }

        ExpenseDisbursement row = new ExpenseDisbursement();
        row.setBusinessId(businessId);
        row.setExpenseId(expense.getId());
        row.setOccurrenceId(dest.occurrenceId());
        row.setScheduleId(dest.scheduleId());
        row.setGatewayType(GatewayType.KOPOKOPO);
        row.setPaymentGatewayConfigId(cfg.getId());
        row.setKopokopoSendMoneyId(result.sendMoneyId());
        row.setAmount(expense.getAmount());
        row.setCurrency("KES");
        row.setDestinationType(SendMoneyRequest.DEST_MOBILE_WALLET);
        row.setDestinationPhone(dest.phone());
        row.setStatus(ExpenseDisbursementStatuses.PENDING);
        try {
            row.setMetadataJson(objectMapper.writeValueAsString(metadata));
        } catch (Exception e) {
            log.warn("Could not serialize expense disbursement metadata", e);
        }
        disbursementRepository.save(row);

        auditEventPublisher.publish(auditEventBuilder
                .builder(AuditEventCategory.FINANCE, AuditEventTypes.EXPENSE_DISBURSEMENT_INITIATED,
                        AuditEventSeverity.INFO)
                .businessId(businessId)
                .branchId(expense.getBranchId())
                .actor(userId, AuditEventActorType.USER)
                .target("expense_disbursement", row.getId())
                .targetLabel(expense.getName())
                .source("web_admin")
                .metadata(Map.of(
                        "expenseId", expense.getId(),
                        "amount", expense.getAmount(),
                        "destinationPhone", maskPhone(dest.phone()),
                        "kopokopoSendMoneyId", result.sendMoneyId()
                ))
                .build());

        log.info("Expense disbursement pending: id={} expense={} kopokopoId={}",
                row.getId(), expenseId, result.sendMoneyId());

        return new ExpenseKopokopoPayResponse(
                true,
                row.getId(),
                result.sendMoneyId(),
                ExpenseDisbursementStatuses.PENDING,
                "Payment sent — waiting for KopoKopo confirmation");
    }

    @Transactional
    public ExpenseKopokopoPayResponse status(String businessId, String expenseId) {
        ExpenseDisbursement d = disbursementRepository
                .findByBusinessIdAndExpenseIdOrderByCreatedAtDesc(businessId, expenseId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No disbursement found"));
        if (isOpenForConfirm(d)) {
            pollSendMoneyStatus(d);
        }
        return toPayResponse(d);
    }

    @Transactional
    public ExpenseKopokopoPayResponse cancel(String businessId, String expenseId, String userId) {
        ExpenseDisbursement d = disbursementRepository
                .findByBusinessIdAndExpenseIdOrderByCreatedAtDesc(businessId, expenseId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No disbursement found"));

        if (ExpenseDisbursementStatuses.SUCCESS.equals(d.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This payment already completed — it cannot be cancelled");
        }
        if (ExpenseDisbursementStatuses.CANCELLED.equals(d.getStatus())) {
            return toPayResponse(d);
        }

        if (isOpenForConfirm(d)) {
            pollSendMoneyStatus(d);
        }
        if (ExpenseDisbursementStatuses.SUCCESS.equals(d.getStatus())) {
            return toPayResponse(d);
        }

        markCancelled(d, "Cancelled — PalMart stopped waiting. If M-Pesa still completes, it will be recorded.");
        auditEventPublisher.publish(auditEventBuilder
                .builder(AuditEventCategory.FINANCE, AuditEventTypes.EXPENSE_DISBURSEMENT_CANCELLED,
                        AuditEventSeverity.INFO)
                .businessId(businessId)
                .actor(userId, AuditEventActorType.USER)
                .target("expense_disbursement", d.getId())
                .source("web_admin")
                .metadata(Map.of("expenseId", expenseId))
                .build());
        return toPayResponse(d);
    }

    /**
     * @return true if this webhook was claimed by an expense disbursement
     */
    @Transactional
    public boolean processKopokopoSendMoneyWebhook(
            String businessId,
            String configId,
            WebhookResult parsed
    ) {
        if (parsed == null || !"send_money".equalsIgnoreCase(parsed.topic())) {
            return false;
        }

        boolean terminal = parsed.success() || parsed.terminalFailure();
        String eventId = parsed.webhookEventId() != null && !parsed.webhookEventId().isBlank()
                ? parsed.webhookEventId()
                : parsed.gatewayCheckoutId();

        ExpenseDisbursement disbursement = resolveDisbursement(businessId, parsed);
        if (disbursement == null) {
            return false;
        }

        if (!isOpenForConfirm(disbursement)) {
            return true;
        }

        if (!terminal) {
            log.info("Expense Send Money intermediate webhook: eventId={} disbursement={}",
                    eventId, disbursement.getId());
            return true;
        }

        if (eventId != null && !eventId.isBlank()) {
            if (webhookEventRepository.existsByGatewayTypeAndGatewayEventId(GatewayType.KOPOKOPO, eventId)) {
                if (parsed.success()) {
                    confirmDisbursement(disbursement, parsed);
                }
                return true;
            }
            try {
                PaymentWebhookEvent audit = new PaymentWebhookEvent();
                audit.setBusinessId(businessId);
                audit.setGatewayType(GatewayType.KOPOKOPO);
                audit.setGatewayEventId(eventId);
                audit.setTopic(parsed.topic());
                audit.setRawPayload(parsed.rawPayload());
                webhookEventRepository.save(audit);
            } catch (DataIntegrityViolationException e) {
                if (parsed.success() && isOpenForConfirm(disbursement)) {
                    confirmDisbursement(disbursement, parsed);
                }
                return true;
            }
        }

        if (parsed.success()) {
            confirmDisbursement(disbursement, parsed);
            return true;
        }
        if (parsed.terminalFailure() && isOpenForConfirm(disbursement)
                && !ExpenseDisbursementStatuses.FAILED.equals(disbursement.getStatus())) {
            markFailed(disbursement, kopokopoDeclineMessage(parsed));
            return true;
        }
        return true;
    }

    @Transactional
    public int pollOpenDisbursements(Instant createdAfter) {
        List<ExpenseDisbursement> candidates = disbursementRepository
                .findByStatusInAndCreatedAtAfterOrderByCreatedAtAsc(
                        List.of(
                                ExpenseDisbursementStatuses.PENDING,
                                ExpenseDisbursementStatuses.FAILED,
                                ExpenseDisbursementStatuses.CANCELLED),
                        createdAfter);
        int settled = 0;
        for (ExpenseDisbursement d : candidates) {
            if (!isOpenForConfirm(d)) {
                continue;
            }
            String before = d.getStatus();
            pollSendMoneyStatus(d);
            if (ExpenseDisbursementStatuses.SUCCESS.equals(d.getStatus())
                    || (ExpenseDisbursementStatuses.FAILED.equals(d.getStatus())
                    && !before.equals(d.getStatus()))) {
                settled++;
            }
        }
        return settled;
    }

    private void pollSendMoneyStatus(ExpenseDisbursement disbursement) {
        if (!isOpenForConfirm(disbursement)) {
            return;
        }
        String sendMoneyId = disbursement.getKopokopoSendMoneyId();
        if (sendMoneyId == null || sendMoneyId.isBlank()) {
            return;
        }
        String configId = disbursement.getPaymentGatewayConfigId();
        if (configId == null || configId.isBlank()) {
            return;
        }
        Optional<PaymentGatewayConfig> cfg = configRepository.findById(configId);
        if (cfg.isEmpty() || cfg.get().getGatewayType() != GatewayType.KOPOKOPO) {
            return;
        }
        try {
            Map<String, String> creds = decryptCredentials(cfg.get());
            WebhookResult status = kopokopoGateway.querySendMoneyStatus(sendMoneyId, creds);
            if (status == null || !"send_money".equalsIgnoreCase(status.topic())) {
                return;
            }
            if (status.success()) {
                confirmDisbursement(disbursement, status);
            } else if (status.terminalFailure()
                    && (ExpenseDisbursementStatuses.PENDING.equals(disbursement.getStatus())
                    || ExpenseDisbursementStatuses.CANCELLED.equals(disbursement.getStatus()))) {
                markFailed(disbursement, kopokopoDeclineMessage(status));
            }
        } catch (Exception e) {
            log.warn("Expense Send Money poll failed for {}: {}", disbursement.getId(), e.getMessage());
        }
    }

    private static boolean isOpenForConfirm(ExpenseDisbursement disbursement) {
        String status = disbursement.getStatus();
        return ExpenseDisbursementStatuses.PENDING.equals(status)
                || ExpenseDisbursementStatuses.FAILED.equals(status)
                || ExpenseDisbursementStatuses.CANCELLED.equals(status);
    }

    private ExpenseKopokopoPayResponse toPayResponse(ExpenseDisbursement d) {
        return new ExpenseKopokopoPayResponse(
                ExpenseDisbursementStatuses.SUCCESS.equals(d.getStatus()),
                d.getId(),
                d.getKopokopoSendMoneyId(),
                d.getStatus(),
                publicDisbursementMessage(d));
    }

    private String publicDisbursementMessage(ExpenseDisbursement d) {
        if (d == null) {
            return null;
        }
        if (ExpenseDisbursementStatuses.PENDING.equals(d.getStatus())) {
            return "Pending — waiting for KopoKopo / M-Pesa confirmation.";
        }
        if (ExpenseDisbursementStatuses.CANCELLED.equals(d.getStatus())) {
            return d.getFailureReason() != null ? d.getFailureReason() : "Cancelled.";
        }
        if (d.getFailureReason() != null && !d.getFailureReason().isBlank()) {
            return d.getFailureReason();
        }
        if (ExpenseDisbursementStatuses.SUCCESS.equals(d.getStatus())) {
            return "Paid via M-Pesa.";
        }
        return d.getStatus();
    }

    private static String kopokopoDeclineMessage(WebhookResult parsed) {
        String detail = parsed.failureMessage();
        if (detail != null && !detail.isBlank()
                && !"Failed".equalsIgnoreCase(detail)
                && !"Error".equalsIgnoreCase(detail)) {
            return "KopoKopo declined: " + detail.trim();
        }
        return "Payment declined by KopoKopo. Check till balance, Send Money permissions, and the payout destination.";
    }

    private void confirmDisbursement(ExpenseDisbursement disbursement, WebhookResult parsed) {
        Expense expense = expenseRepository
                .findByIdAndBusinessId(disbursement.getExpenseId(), disbursement.getBusinessId())
                .orElse(null);
        if (expense == null) {
            markFailed(disbursement, "Expense not found after Send Money success");
            return;
        }

        disbursement.setStatus(ExpenseDisbursementStatuses.SUCCESS);
        disbursement.setConfirmedAt(Instant.now());
        disbursement.setFailureReason(null);
        disbursementRepository.save(disbursement);

        if (expense.getPaidAt() == null) {
            expense.setPaidAt(Instant.now());
            expenseRepository.save(expense);
        }

        auditEventPublisher.publish(auditEventBuilder
                .builder(AuditEventCategory.FINANCE, AuditEventTypes.EXPENSE_DISBURSEMENT_CONFIRMED,
                        AuditEventSeverity.INFO)
                .businessId(disbursement.getBusinessId())
                .branchId(expense.getBranchId())
                .actor(null, AuditEventActorType.SYSTEM)
                .target("expense_disbursement", disbursement.getId())
                .targetLabel(expense.getName())
                .source("kopokopo_webhook")
                .metadata(Map.of(
                        "expenseId", expense.getId(),
                        "amount", expense.getAmount(),
                        "gatewayTxn", parsed.gatewayTransactionId() == null
                                ? ""
                                : parsed.gatewayTransactionId()
                ))
                .build());

        log.info("Expense disbursement confirmed: id={} expense={}",
                disbursement.getId(), expense.getId());
    }

    private void markCancelled(ExpenseDisbursement disbursement, String reason) {
        disbursement.setStatus(ExpenseDisbursementStatuses.CANCELLED);
        disbursement.setFailureReason(reason);
        disbursementRepository.save(disbursement);
    }

    private void markFailed(ExpenseDisbursement disbursement, String reason) {
        disbursement.setStatus(ExpenseDisbursementStatuses.FAILED);
        disbursement.setFailureReason(reason);
        disbursementRepository.save(disbursement);

        auditEventPublisher.publish(auditEventBuilder
                .builder(AuditEventCategory.FINANCE, AuditEventTypes.EXPENSE_DISBURSEMENT_FAILED,
                        AuditEventSeverity.WARN)
                .businessId(disbursement.getBusinessId())
                .actor(null, AuditEventActorType.SYSTEM)
                .target("expense_disbursement", disbursement.getId())
                .source("kopokopo")
                .metadata(Map.of(
                        "expenseId", disbursement.getExpenseId(),
                        "reason", reason == null ? "" : reason
                ))
                .build());
        log.warn("Expense disbursement failed: id={} reason={}", disbursement.getId(), reason);
    }

    private ExpenseDisbursement resolveDisbursement(String businessId, WebhookResult parsed) {
        if (parsed.gatewayCheckoutId() != null && !parsed.gatewayCheckoutId().isBlank()) {
            Optional<ExpenseDisbursement> byKk = disbursementRepository.findByKopokopoSendMoneyId(
                    parsed.gatewayCheckoutId().trim());
            if (byKk.isPresent() && businessId.equals(byKk.get().getBusinessId())) {
                return byKk.get();
            }
        }
        if (parsed.reference() != null && !parsed.reference().isBlank()) {
            return disbursementRepository
                    .findFirstByBusinessIdAndExpenseIdAndStatusOrderByCreatedAtDesc(
                            businessId,
                            parsed.reference().trim(),
                            ExpenseDisbursementStatuses.PENDING)
                    .orElse(null);
        }
        return null;
    }

    private Optional<ExpenseDisbursement> findPendingDisbursement(String businessId, String expenseId) {
        Optional<ExpenseDisbursement> pending = disbursementRepository
                .findFirstByBusinessIdAndExpenseIdAndStatusOrderByCreatedAtDesc(
                        businessId, expenseId, ExpenseDisbursementStatuses.PENDING);
        pending.ifPresent(this::pollSendMoneyStatus);
        if (pending.isPresent() && ExpenseDisbursementStatuses.PENDING.equals(pending.get().getStatus())) {
            return pending;
        }
        return Optional.empty();
    }

    private boolean hasSuccessfulDisbursement(String businessId, String expenseId) {
        return disbursementRepository
                .findByBusinessIdAndExpenseIdOrderByCreatedAtDesc(businessId, expenseId)
                .stream()
                .anyMatch(d -> ExpenseDisbursementStatuses.SUCCESS.equals(d.getStatus()));
    }

    private Expense requirePostedExpense(String businessId, String expenseId) {
        Expense expense = expenseRepository.findByIdAndBusinessId(expenseId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Expense not found"));
        if (!FinanceConstants.EXPENSE_APPROVAL_POSTED.equals(expense.getApprovalStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Expense must be posted (approved) before Send Money");
        }
        if (expense.getJournalEntryId() == null || expense.getJournalEntryId().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Expense has no journal entry — post to books first");
        }
        return expense;
    }

    private Destination resolveDestination(Expense expense) {
        String phone = normalizePhone(expense.getVendorMpesaNumber());
        String occurrenceId = null;
        String scheduleId = null;

        if (phone == null) {
            Optional<ExpenseScheduleOccurrence> occ = occurrenceRepository
                    .findByExpenseIdAndBusinessId(expense.getId(), expense.getBusinessId());
            if (occ.isPresent()) {
                occurrenceId = occ.get().getId();
                scheduleId = occ.get().getScheduleId();
                ExpenseSchedule schedule = scheduleRepository
                        .findByIdAndBusinessId(scheduleId, expense.getBusinessId())
                        .orElse(null);
                if (schedule != null) {
                    phone = normalizePhone(schedule.getVendorMpesaNumber());
                }
            }
        } else {
            Optional<ExpenseScheduleOccurrence> occ = occurrenceRepository
                    .findByExpenseIdAndBusinessId(expense.getId(), expense.getBusinessId());
            if (occ.isPresent()) {
                occurrenceId = occ.get().getId();
                scheduleId = occ.get().getScheduleId();
            }
        }
        return new Destination(phone, occurrenceId, scheduleId);
    }

    private static String normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = StkPhoneNormalizer.normalize(raw);
        return normalized == null || normalized.isBlank() ? null : normalized;
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) {
            return "••••";
        }
        return phone.substring(0, 4) + "••••" + phone.substring(phone.length() - 3);
    }

    private Map<String, String> decryptCredentials(PaymentGatewayConfig cfg) {
        try {
            String json = encryptionService.decrypt(cfg.getCredentialsJson());
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read gateway credentials");
        }
    }

    private record Destination(String phone, String occurrenceId, String scheduleId) {
    }
}
