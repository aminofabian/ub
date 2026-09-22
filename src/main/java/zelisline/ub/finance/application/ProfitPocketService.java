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
import zelisline.ub.finance.api.dto.ProfitPocketTestResponse;
import zelisline.ub.finance.domain.JournalEntry;
import zelisline.ub.finance.domain.ProfitPocket;
import zelisline.ub.finance.repository.JournalReportRepository;
import zelisline.ub.finance.repository.ProfitPocketRepository;
import zelisline.ub.payments.api.dto.ProfitPocketSettingsResponse;
import zelisline.ub.payments.application.PlatformDarajaSettingsService;
import zelisline.ub.payments.application.ProfitPocketSettingsService;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.ProfitPocketSettings;
import zelisline.ub.payments.domain.spi.SendMoneyRequest;
import zelisline.ub.payments.domain.spi.SendMoneyResult;
import zelisline.ub.payments.domain.spi.WebhookResult;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.payments.infrastructure.DarajaPaymentGateway;
import zelisline.ub.payments.infrastructure.KopokopoPaymentGateway;
import zelisline.ub.sales.api.dto.PaymentMethodBreakdownRow;
import zelisline.ub.sales.application.SalesIntelligenceService;
import zelisline.ub.tenancy.repository.BranchRepository;

import org.springframework.beans.factory.ObjectProvider;

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
    private final SalesIntelligenceService salesIntelligenceService;
    private final FinanceReportsService financeReportsService;
    private final JournalReportRepository journalReportRepository;
    private final LedgerAccountResolver ledgerAccountResolver;
    private final LedgerPostingPort ledgerPostingPort;
    private final BranchRepository branchRepository;
    private final ObjectMapper objectMapper;
    private final CredentialEncryptionService encryptionService;
    private final KopokopoPaymentGateway kopokopoGateway;
    private final DarajaPaymentGateway darajaPaymentGateway;
    private final ObjectProvider<PlatformDarajaSettingsService> platformDarajaSettingsService;

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
        BigDecimal rawSurplus = cash.add(mpesa).subtract(leaveFloat).setScale(2, RoundingMode.HALF_UP);
        if (rawSurplus.signum() < 0) {
            rawSurplus = ZERO;
        }
        ProfitAndLossResponse pl = financeReportsService.profitAndLoss(businessId, from, to, resolvedBranch);
        BigDecimal grossProfit = money(pl.grossProfit());
        BigDecimal profitBase = grossProfit.signum() > 0 ? grossProfit : ZERO;
        // Suggest from profit, never more than liquid cash surplus.
        BigDecimal pocketBase = profitBase.min(rawSurplus);
        BigDecimal jarPct = ProfitPocketSettingsService.effectiveJarPct(settings.profitJarPct());
        BigDecimal suggested = pocketBase
                .multiply(jarPct)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);

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
                grossProfit,
                openShifts,
                settings.enabled() && settings.configured() && !settings.collidesWithCustomerPay(),
                settings.destinationSummary(),
                settings.collidesWithCustomerPay(),
                settings.customerPayCollisionMessage(),
                jarPct,
                rawSurplus);
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
        if (!ProfitPocketSettings.TYPE_BANK.equals(type)
                && !ProfitPocketSettings.TYPE_TILL.equals(type)
                && !ProfitPocketSettings.TYPE_PAYBILL.equals(type)) {
            row.setSendMoneyStatus(SEND_SKIPPED);
            row.setSendMoneyMessage("Destination type does not support Send Money");
            return;
        }

        String rail = profitPocketSettingsService.resolveSendRail(businessId, settings);
        if (rail == null) {
            row.setSendMoneyStatus(SEND_SKIPPED);
            row.setSendMoneyMessage(
                    "No send rail ready — choose Daraja or KopoKopo in Profit Pocket settings");
            return;
        }

        if (ProfitPocketSettings.RAIL_DARAJA.equals(rail)) {
            initiateDarajaB2B(businessId, settings, row.getAmount(), "Profit pocket", result -> {
                if (!result.accepted()) {
                    row.setSendMoneyStatus(SEND_FAILED);
                    row.setSendMoneyMessage(truncate(
                            result.message() != null ? result.message() : "Daraja B2B declined", 500));
                    return;
                }
                String id = firstNonBlank(result.conversationId(), result.originatorConversationId());
                row.setSendMoneyStatus(SEND_PENDING);
                row.setKopokopoSendMoneyId(id);
                row.setSendMoneyMessage("Daraja B2B submitted — waiting for Safaricom");
                log.info("Profit pocket Daraja B2B pending pocket={} conversationId={}", row.getId(), id);
            }, err -> {
                row.setSendMoneyStatus(SEND_FAILED);
                row.setSendMoneyMessage(truncate(err, 500));
            });
            return;
        }

        initiateKopokopoSendMoney(businessId, settings, row.getAmount(), "Profit pocket",
                row.getId(), (status, sendId, message, cfgId) -> {
                    row.setSendMoneyStatus(status);
                    row.setKopokopoSendMoneyId(sendId);
                    row.setSendMoneyMessage(message);
                    row.setPaymentGatewayConfigId(cfgId);
                });
    }

    /**
     * Sends KES 1 to the configured pocket destination so the owner can confirm the rail.
     * Does not create a pocket journal entry.
     */
    @Transactional
    public ProfitPocketTestResponse testDestination(String businessId) {
        ProfitPocketSettings settings = profitPocketSettingsService.requireConfigured(businessId);
        String rail = profitPocketSettingsService.resolveSendRail(businessId, settings);
        if (rail == null) {
            return new ProfitPocketTestResponse(
                    "skipped",
                    null,
                    "Choose a ready send rail (Daraja or KopoKopo) in Profit Pocket settings.");
        }

        if (ProfitPocketSettings.RAIL_DARAJA.equals(rail)) {
            final ProfitPocketTestResponse[] box = new ProfitPocketTestResponse[1];
            initiateDarajaB2B(businessId, settings, new BigDecimal("1.00"), "Profit Pocket destination test",
                    result -> {
                        if (!result.accepted()) {
                            box[0] = new ProfitPocketTestResponse(
                                    "failed",
                                    null,
                                    result.message() != null ? result.message() : "Daraja B2B declined");
                            return;
                        }
                        String id = firstNonBlank(result.conversationId(), result.originatorConversationId());
                        box[0] = new ProfitPocketTestResponse(
                                "pending",
                                id,
                                "KES 1 via Daraja B2B — check the destination. Waiting for Safaricom.");
                    },
                    err -> box[0] = new ProfitPocketTestResponse("failed", null, err));
            return box[0] != null
                    ? box[0]
                    : new ProfitPocketTestResponse("failed", null, "Daraja test did not complete");
        }

        final ProfitPocketTestResponse[] box = new ProfitPocketTestResponse[1];
        initiateKopokopoSendMoney(businessId, settings, new BigDecimal("1.00"),
                "Profit Pocket destination test", null,
                (status, sendId, message, cfgId) ->
                        box[0] = new ProfitPocketTestResponse(status, sendId, message));
        return box[0] != null
                ? box[0]
                : new ProfitPocketTestResponse("failed", null, "KopoKopo test did not complete");
    }

    @Transactional
    public boolean processDarajaB2BResult(DarajaPaymentGateway.B2BResult result) {
        if (result == null) {
            return false;
        }
        Optional<ProfitPocket> opt = Optional.empty();
        if (result.conversationId() != null && !result.conversationId().isBlank()) {
            opt = profitPocketRepository.findFirstByKopokopoSendMoneyId(result.conversationId().trim());
        }
        if (opt.isEmpty()
                && result.originatorConversationId() != null
                && !result.originatorConversationId().isBlank()) {
            opt = profitPocketRepository.findFirstByKopokopoSendMoneyId(
                    result.originatorConversationId().trim());
        }
        if (opt.isEmpty()) {
            return false;
        }
        ProfitPocket row = opt.get();
        if (SEND_SUCCESS.equals(row.getSendMoneyStatus()) || SEND_FAILED.equals(row.getSendMoneyStatus())) {
            return true;
        }
        if (result.accepted()) {
            row.setSendMoneyStatus(SEND_SUCCESS);
            row.setSendMoneyMessage("Daraja confirmed the transfer");
        } else {
            row.setSendMoneyStatus(SEND_FAILED);
            String msg = result.message() != null ? result.message() : "Daraja B2B failed";
            row.setSendMoneyMessage(truncate(msg, 500));
        }
        profitPocketRepository.save(row);
        log.info("Profit pocket Daraja B2B {} pocket={}", row.getSendMoneyStatus(), row.getId());
        return true;
    }

    private void initiateDarajaB2B(
            String businessId,
            ProfitPocketSettings settings,
            BigDecimal amount,
            String remarks,
            java.util.function.Consumer<DarajaPaymentGateway.B2BResult> onResult,
            java.util.function.Consumer<String> onError
    ) {
        PlatformDarajaSettingsService daraja = platformDarajaSettingsService.getIfAvailable();
        if (daraja == null || !daraja.isB2bConfigured()) {
            onError.accept("Platform Daraja B2B is not configured by the administrator");
            return;
        }
        Map<String, String> creds = daraja.credentials().orElse(Map.of());
        if (creds.isEmpty()) {
            onError.accept("Platform Daraja credentials missing");
            return;
        }

        String destType;
        String partyB;
        String accountRef;
        String type = settings.getDestinationType();
        if (ProfitPocketSettings.TYPE_TILL.equals(type)) {
            destType = SendMoneyRequest.DEST_TILL;
            partyB = settings.getDestinationAccount();
            accountRef = null;
        } else if (ProfitPocketSettings.TYPE_PAYBILL.equals(type)) {
            destType = SendMoneyRequest.DEST_PAYBILL;
            partyB = settings.getDestinationPaybill();
            accountRef = settings.getDestinationPaybillAccount();
        } else if (ProfitPocketSettings.TYPE_BANK.equals(type)) {
            destType = SendMoneyRequest.DEST_PAYBILL;
            partyB = settings.getDestinationPaybill();
            accountRef = settings.getDestinationAccount();
        } else {
            onError.accept("Unsupported destination for Daraja B2B");
            return;
        }

        try {
            DarajaPaymentGateway.B2BRequest request = new DarajaPaymentGateway.B2BRequest(
                    creds,
                    publicApiBaseUrl.replaceAll("/+$", ""),
                    destType,
                    partyB,
                    accountRef,
                    amount,
                    "KES",
                    remarks);
            onResult.accept(darajaPaymentGateway.sendB2B(request));
        } catch (Exception e) {
            log.warn("Profit pocket Daraja B2B failed: {}", e.toString());
            onError.accept(e.getMessage() != null ? e.getMessage() : "Daraja B2B failed");
        }
    }

    private interface KopokopoSendCallback {
        void accept(String status, String sendMoneyId, String message, String configId);
    }

    private void initiateKopokopoSendMoney(
            String businessId,
            ProfitPocketSettings settings,
            BigDecimal amount,
            String description,
            String profitPocketId,
            KopokopoSendCallback callback
    ) {
        Optional<PaymentGatewayConfig> gateway =
                profitPocketSettingsService.resolveKopokopoConfig(businessId);
        if (gateway.isEmpty()) {
            callback.accept(
                    SEND_SKIPPED,
                    null,
                    "No active KopoKopo gateway — connect KopoKopo and enable Pay suppliers.",
                    null);
            return;
        }
        PaymentGatewayConfig cfg = gateway.get();
        if (cfg.getGatewayType() != GatewayType.KOPOKOPO) {
            callback.accept(
                    SEND_SKIPPED,
                    null,
                    "Send Money via " + cfg.getGatewayType().name() + " is not implemented yet",
                    cfg.getId());
            return;
        }

        try {
            Map<String, String> creds = decryptCredentials(cfg);
            String till = creds.getOrDefault("tillNumber", creds.get("shortcode"));
            Map<String, String> metadata = new LinkedHashMap<>();
            if (profitPocketId != null) {
                metadata.put("profitPocketId", profitPocketId);
            } else {
                metadata.put("profitPocketTest", "true");
            }
            metadata.put("businessId", businessId);
            metadata.put("destinationType", settings.getDestinationType());

            SendMoneyRequest request = buildSendMoneyRequest(
                    settings,
                    creds,
                    publicApiBaseUrl.replaceAll("/+$", ""),
                    amount,
                    description,
                    till,
                    metadata);
            SendMoneyResult result = kopokopoGateway.sendMoney(request);
            if (!result.accepted() || result.sendMoneyId() == null) {
                callback.accept(
                        SEND_FAILED,
                        null,
                        truncate(
                                result.message() != null ? result.message() : "KopoKopo Send Money declined",
                                500),
                        cfg.getId());
                return;
            }
            callback.accept(
                    SEND_PENDING,
                    result.sendMoneyId(),
                    "Send Money submitted — waiting for KopoKopo",
                    cfg.getId());
            log.info("Profit pocket KopoKopo Send Money pending pocket={} kopokopoId={}",
                    profitPocketId, result.sendMoneyId());
        } catch (Exception e) {
            log.warn("Profit pocket KopoKopo Send Money failed soft: {}", e.toString());
            callback.accept(
                    SEND_FAILED,
                    null,
                    truncate(
                            e instanceof ResponseStatusException rse && rse.getReason() != null
                                    ? rse.getReason()
                                    : "Could not send money: " + e.getMessage(),
                            500),
                    cfg.getId());
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
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
        if (ProfitPocketSettings.TYPE_BANK.equals(type)) {
            // Bank Lipa Na M-Pesa = paybill business number + account number.
            return new SendMoneyRequest(
                    creds,
                    callbackBase,
                    SendMoneyRequest.DEST_PAYBILL,
                    null,
                    null,
                    settings.getDestinationPaybill(),
                    settings.getDestinationAccount(),
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
