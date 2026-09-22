package zelisline.ub.finance.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.finance.FinanceConstants;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.api.dto.CashSurplusResponse;
import zelisline.ub.finance.api.dto.PostProfitPocketRequest;
import zelisline.ub.finance.api.dto.ProfitAndLossResponse;
import zelisline.ub.finance.api.dto.ProfitPocketResponse;
import zelisline.ub.finance.domain.JournalEntry;
import zelisline.ub.finance.domain.ProfitPocket;
import zelisline.ub.finance.repository.JournalReportRepository;
import zelisline.ub.finance.repository.ProfitPocketRepository;
import zelisline.ub.payments.api.dto.ProfitPocketSettingsResponse;
import zelisline.ub.payments.application.ProfitPocketSettingsService;
import zelisline.ub.payments.application.SupplierPayoutSettingsService;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.ProfitPocketSettings;
import zelisline.ub.payments.domain.spi.SendMoneyRequest;
import zelisline.ub.payments.domain.spi.SendMoneyResult;
import zelisline.ub.payments.domain.spi.WebhookResult;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.payments.infrastructure.KopokopoPaymentGateway;
import zelisline.ub.sales.api.dto.PaymentMethodBreakdownRow;
import zelisline.ub.sales.application.SalesIntelligenceService;
import zelisline.ub.tenancy.repository.BranchRepository;

@Service
@RequiredArgsConstructor
public class ProfitPocketService {

    private static final Logger log = LoggerFactory.getLogger(ProfitPocketService.class);
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    public static final String SEND_PENDING = "pending";
    public static final String SEND_SUCCESS = "success";
    public static final String SEND_FAILED = "failed";
    public static final String SEND_SKIPPED = "skipped";

    private final ProfitPocketRepository profitPocketRepository;
    private final ProfitPocketSettingsService profitPocketSettingsService;
    private final SupplierPayoutSettingsService supplierPayoutSettingsService;
    private final SalesIntelligenceService salesIntelligenceService;
    private final FinanceReportsService financeReportsService;
    private final JournalReportRepository journalReportRepository;
    private final LedgerAccountResolver ledgerAccountResolver;
    private final LedgerPostingPort ledgerPostingPort;
    private final BranchRepository branchRepository;
    private final ObjectMapper objectMapper;
    private final CredentialEncryptionService encryptionService;
    private final KopokopoPaymentGateway kopokopoGateway;

    @Value("${app.public.api-base-url:http://localhost:5050}")
    private String publicApiBaseUrl;

    @Transactional(readOnly = true)
    public CashSurplusResponse cashSurplus(
            String businessId,
            LocalDate from,
            LocalDate to,
            String branchId
    ) {
        if (from == null || to == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from and to are required");
        }
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "to must be on or after from");
        }
        String resolvedBranch = blankToNull(branchId);
        if (resolvedBranch != null) {
            branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(resolvedBranch, businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
        }

        List<PaymentMethodBreakdownRow> methods =
                salesIntelligenceService.paymentsByMethod(businessId, from, to, resolvedBranch, null);
        BigDecimal cash = ZERO;
        BigDecimal mpesa = ZERO;
        BigDecimal credit = ZERO;
        for (PaymentMethodBreakdownRow row : methods) {
            BigDecimal amt = money(row.totalAmount());
            String method = row.method() == null ? "" : row.method().trim().toLowerCase();
            if ("cash".equals(method)) {
                cash = cash.add(amt);
            } else if (method.contains("mpesa")) {
                mpesa = mpesa.add(amt);
            } else if ("customer_credit".equals(method) || "credit".equals(method)) {
                credit = credit.add(amt);
            }
        }

        ProfitPocketSettingsResponse settings = profitPocketSettingsService.getSettings(businessId);
        BigDecimal leaveFloat = money(settings.defaultFloat());
        BigDecimal suggested = cash.add(mpesa).subtract(leaveFloat).setScale(2, RoundingMode.HALF_UP);
        if (suggested.signum() < 0) {
            suggested = ZERO;
        }

        ProfitAndLossResponse pl = financeReportsService.profitAndLoss(businessId, from, to, resolvedBranch);
        long openShifts = journalReportRepository.countOpenShifts(businessId, resolvedBranch);

        return new CashSurplusResponse(
                from,
                to,
                resolvedBranch,
                cash,
                mpesa,
                credit,
                leaveFloat,
                suggested,
                money(pl.grossProfit()),
                openShifts,
                settings.enabled() && settings.configured() && !settings.collidesWithCustomerPay(),
                settings.destinationSummary(),
                settings.collidesWithCustomerPay(),
                settings.customerPayCollisionMessage());
    }

    @Transactional
    public ProfitPocketResponse post(
            String businessId,
            PostProfitPocketRequest req,
            String userId
    ) {
        if (req.periodFrom() == null || req.periodTo() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "periodFrom and periodTo are required");
        }
        if (req.periodTo().isBefore(req.periodFrom())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "periodTo must be on or after periodFrom");
        }
        BigDecimal amount = money(req.amount());
        if (amount.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount must be > 0");
        }
        BigDecimal leaveFloat = req.leaveFloat() == null ? ZERO : money(req.leaveFloat());
        if (leaveFloat.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "leaveFloat must be >= 0");
        }

        String branchId = blankToNull(req.branchId());
        if (branchId != null) {
            branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
        }

        ProfitPocketSettings settings = profitPocketSettingsService.requireConfigured(businessId);
        String funding = normalizeFunding(req.fundingMethod());

        CashSurplusResponse surplus =
                cashSurplus(businessId, req.periodFrom(), req.periodTo(), branchId);

        String pocketId = UUID.randomUUID().toString();
        String drawingsId = ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.OWNER_DRAWINGS);
        String creditAcc = ledgerAccountResolver.resolveId(businessId, creditCode(funding));

        JournalEntry entry = new JournalEntry();
        entry.setBusinessId(businessId);
        entry.setBranchId(branchId);
        entry.setEntryDate(LocalDate.now());
        entry.setSourceType(FinanceConstants.JOURNAL_SOURCE_PROFIT_POCKET);
        entry.setSourceId(pocketId);
        entry.setMemo("Profit pocket " + ProfitPocketSettingsService.summarize(settings));
        entry.debit(drawingsId, amount);
        entry.credit(creditAcc, amount);
        String jeId = ledgerPostingPort.post(entry);

        ProfitPocket row = new ProfitPocket();
        row.setId(pocketId);
        row.setBusinessId(businessId);
        row.setBranchId(branchId);
        row.setPeriodFrom(req.periodFrom());
        row.setPeriodTo(req.periodTo());
        row.setAmount(amount);
        row.setLeaveFloat(leaveFloat);
        row.setSuggestedSurplus(surplus.suggestedPocket());
        row.setFundingMethod(funding);
        row.setDestinationType(settings.getDestinationType());
        row.setDestinationSnapshotJson(snapshotJson(settings));
        row.setWarningsJson(warningsJson(req.acknowledgedWarnings()));
        row.setJournalEntryId(jeId);
        row.setCreatedBy(userId);
        row.setCreatedAt(Instant.now());

        maybeInitiateSendMoney(businessId, settings, row);
        profitPocketRepository.save(row);

        return toResponse(row, ProfitPocketSettingsService.summarize(settings));
    }

    @Transactional(readOnly = true)
    public ProfitPocketResponse get(String businessId, String pocketId) {
        ProfitPocket row = profitPocketRepository.findByIdAndBusinessId(pocketId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Profit pocket not found"));
        return toResponse(row, summaryFromSnapshot(row.getDestinationSnapshotJson()));
    }

    @Transactional(readOnly = true)
    public List<ProfitPocketResponse> list(String businessId, int limit) {
        int cap = Math.min(Math.max(limit, 1), 50);
        return profitPocketRepository
                .findByBusinessIdOrderByCreatedAtDesc(
                        businessId, org.springframework.data.domain.PageRequest.of(0, cap))
                .stream()
                .map(row -> toResponse(row, summaryFromSnapshot(row.getDestinationSnapshotJson())))
                .toList();
    }

    /**
     * Claim a KopoKopo Send Money webhook for a Profit Pocket transfer.
     *
     * @return true when this event belonged to a profit pocket
     */
    @Transactional
    public boolean processKopokopoSendMoneyWebhook(
            String businessId,
            String gatewayConfigId,
            WebhookResult parsed
    ) {
        String sendMoneyId = parsed.gatewayTransactionId();
        if (sendMoneyId == null || sendMoneyId.isBlank()) {
            return false;
        }
        Optional<ProfitPocket> found =
                profitPocketRepository.findByBusinessIdAndKopokopoSendMoneyId(businessId, sendMoneyId.trim());
        if (found.isEmpty()) {
            return false;
        }
        ProfitPocket row = found.get();
        if (SEND_SUCCESS.equals(row.getSendMoneyStatus()) || SEND_FAILED.equals(row.getSendMoneyStatus())) {
            return true;
        }
        if (gatewayConfigId != null
                && row.getPaymentGatewayConfigId() != null
                && !gatewayConfigId.equals(row.getPaymentGatewayConfigId())) {
            return false;
        }
        if (parsed.success()) {
            row.setSendMoneyStatus(SEND_SUCCESS);
            row.setSendMoneyMessage("KopoKopo confirmed the transfer");
        } else {
            row.setSendMoneyStatus(SEND_FAILED);
            String msg = parsed.failureMessage() != null ? parsed.failureMessage() : "Send Money failed";
            row.setSendMoneyMessage(truncate(msg, 500));
        }
        profitPocketRepository.save(row);
        log.info("Profit pocket Send Money {} pocket={} kopokopoId={}",
                row.getSendMoneyStatus(), row.getId(), sendMoneyId);
        return true;
    }

    private void maybeInitiateSendMoney(
            String businessId,
            ProfitPocketSettings settings,
            ProfitPocket row
    ) {
        String type = settings.getDestinationType();
        if (ProfitPocketSettings.TYPE_BANK.equals(type)) {
            row.setSendMoneyStatus(SEND_SKIPPED);
            row.setSendMoneyMessage("Bank destination — recorded on books only; transfer manually");
            return;
        }
        if (!ProfitPocketSettings.TYPE_MPESA_PHONE.equals(type)
                && !ProfitPocketSettings.TYPE_TILL.equals(type)
                && !ProfitPocketSettings.TYPE_PAYBILL.equals(type)) {
            row.setSendMoneyStatus(SEND_SKIPPED);
            row.setSendMoneyMessage("Destination type does not support Send Money");
            return;
        }

        Optional<PaymentGatewayConfig> gateway =
                supplierPayoutSettingsService.resolveActivePayoutConfig(businessId);
        if (gateway.isEmpty()) {
            row.setSendMoneyStatus(SEND_SKIPPED);
            row.setSendMoneyMessage(
                    "No active supplier-payout gateway — pocket recorded on books. "
                            + "Enable Payments → Pay suppliers to auto-send.");
            return;
        }
        PaymentGatewayConfig cfg = gateway.get();
        if (cfg.getGatewayType() != GatewayType.KOPOKOPO) {
            row.setSendMoneyStatus(SEND_SKIPPED);
            row.setSendMoneyMessage("Send Money via " + cfg.getGatewayType().name() + " is not implemented yet");
            return;
        }

        try {
            Map<String, String> creds = decryptCredentials(cfg);
            String till = creds.getOrDefault("tillNumber", creds.get("shortcode"));
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("profitPocketId", row.getId());
            metadata.put("businessId", businessId);
            metadata.put("destinationType", type);

            SendMoneyRequest request = buildSendMoneyRequest(
                    settings,
                    creds,
                    publicApiBaseUrl.replaceAll("/+$", ""),
                    row.getAmount(),
                    "Profit pocket",
                    till,
                    metadata);
            SendMoneyResult result = kopokopoGateway.sendMoney(request);
            if (!result.accepted() || result.sendMoneyId() == null) {
                row.setSendMoneyStatus(SEND_FAILED);
                row.setSendMoneyMessage(truncate(
                        result.message() != null ? result.message() : "KopoKopo Send Money declined",
                        500));
                row.setPaymentGatewayConfigId(cfg.getId());
                return;
            }
            row.setSendMoneyStatus(SEND_PENDING);
            row.setKopokopoSendMoneyId(result.sendMoneyId());
            row.setPaymentGatewayConfigId(cfg.getId());
            row.setSendMoneyMessage("Send Money submitted — waiting for KopoKopo");
            log.info("Profit pocket Send Money pending pocket={} kopokopoId={}",
                    row.getId(), result.sendMoneyId());
        } catch (Exception e) {
            log.warn("Profit pocket Send Money failed soft: {}", e.toString());
            row.setSendMoneyStatus(SEND_FAILED);
            row.setSendMoneyMessage(truncate(
                    e instanceof ResponseStatusException rse && rse.getReason() != null
                            ? rse.getReason()
                            : "Could not send money: " + e.getMessage(),
                    500));
            row.setPaymentGatewayConfigId(cfg.getId());
        }
    }

    private static SendMoneyRequest buildSendMoneyRequest(
            ProfitPocketSettings settings,
            Map<String, String> creds,
            String callbackBase,
            BigDecimal amount,
            String description,
            String sourceIdentifier,
            Map<String, String> metadata
    ) {
        String type = settings.getDestinationType();
        if (ProfitPocketSettings.TYPE_MPESA_PHONE.equals(type)) {
            return new SendMoneyRequest(
                    creds,
                    callbackBase,
                    SendMoneyRequest.DEST_MOBILE_WALLET,
                    settings.getDestinationAccount(),
                    null,
                    null,
                    null,
                    amount,
                    "KES",
                    description,
                    sourceIdentifier,
                    metadata);
        }
        if (ProfitPocketSettings.TYPE_TILL.equals(type)) {
            return new SendMoneyRequest(
                    creds,
                    callbackBase,
                    SendMoneyRequest.DEST_TILL,
                    null,
                    settings.getDestinationAccount(),
                    null,
                    null,
                    amount,
                    "KES",
                    description,
                    sourceIdentifier,
                    metadata);
        }
        if (ProfitPocketSettings.TYPE_PAYBILL.equals(type)) {
            return new SendMoneyRequest(
                    creds,
                    callbackBase,
                    SendMoneyRequest.DEST_PAYBILL,
                    null,
                    null,
                    settings.getDestinationPaybill(),
                    settings.getDestinationPaybillAccount(),
                    amount,
                    "KES",
                    description,
                    sourceIdentifier,
                    metadata);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported destination for Send Money");
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

    private ProfitPocketResponse toResponse(ProfitPocket row, String summary) {
        return new ProfitPocketResponse(
                row.getId(),
                row.getJournalEntryId(),
                row.getAmount(),
                row.getPeriodFrom(),
                row.getPeriodTo(),
                summary,
                row.getCreatedAt(),
                row.getSendMoneyStatus(),
                row.getKopokopoSendMoneyId(),
                row.getSendMoneyMessage());
    }

    private String summaryFromSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            Object summary = map.get("summary");
            return summary != null ? summary.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String snapshotJson(ProfitPocketSettings settings) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", settings.getDestinationType());
        map.put("label", settings.getDestinationLabel());
        map.put("account", settings.getDestinationAccount());
        map.put("bankName", settings.getDestinationBankName());
        map.put("paybill", settings.getDestinationPaybill());
        map.put("paybillAccount", settings.getDestinationPaybillAccount());
        map.put("summary", ProfitPocketSettingsService.summarize(settings));
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String warningsJson(List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(warnings);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String normalizeFunding(String raw) {
        String m = raw == null ? FinanceConstants.EXPENSE_PAY_METHOD_CASH : raw.trim().toLowerCase();
        if (FinanceConstants.EXPENSE_PAY_METHOD_CASH.equals(m)
                || FinanceConstants.EXPENSE_PAY_METHOD_MPESA_MANUAL.equals(m)
                || FinanceConstants.EXPENSE_PAY_METHOD_BANK.equals(m)) {
            return m;
        }
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "fundingMethod must be cash, mpesa_manual, or bank");
    }

    private static String creditCode(String funding) {
        return switch (funding) {
            case FinanceConstants.EXPENSE_PAY_METHOD_MPESA_MANUAL -> LedgerAccountCodes.MPESA_CLEARING;
            case FinanceConstants.EXPENSE_PAY_METHOD_BANK -> LedgerAccountCodes.BANK_ACCOUNT;
            default -> LedgerAccountCodes.OPERATING_CASH;
        };
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private static String truncate(String raw, int max) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
