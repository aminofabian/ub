package zelisline.ub.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.payments.domain.GatewayStatus;
import zelisline.ub.payments.domain.GatewayStkPush;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.PlatformCustodySettlement;
import zelisline.ub.payments.domain.PlatformCustodySettlementStatuses;
import zelisline.ub.payments.domain.PlatformMpesaCustodyProviders;
import zelisline.ub.payments.domain.StkPushContextType;
import zelisline.ub.payments.domain.spi.SendMoneyResult;
import zelisline.ub.payments.domain.spi.ValidationResult;
import zelisline.ub.payments.domain.spi.WebhookResult;
import zelisline.ub.payments.infrastructure.DarajaPaymentGateway;
import zelisline.ub.payments.infrastructure.KopokopoPaymentGateway;
import zelisline.ub.payments.repository.PaymentGatewayConfigRepository;
import zelisline.ub.payments.repository.PlatformCustodySettlementRepository;

/**
 * Model B custody: same-rail collect/settle, webhook matching, in-flight reconcile,
 * ops retry, destination validation.
 */
@SuppressWarnings("unchecked")
class PlatformCustodySettlementServiceTest {

    private static final String BUSINESS = "b1";
    private static final String CONFIG_ID = "cfg-custody";
    private static final Map<String, String> CREDS =
            Map.of("clientId", "c", "clientSecret", "s", "apiKey", "k", "tillNumber", "123456");
    private static final Map<String, String> DARAJA_CREDS =
            Map.of("consumerKey", "ck", "consumerSecret", "cs", "shortcode", "600000",
                    "initiatorName", "kioskapi", "initiatorPassword", "pw");

    private PlatformCustodySettlementRepository settlementRepository;
    private PaymentGatewayConfigRepository configRepository;
    private KopokopoPaymentGateway kopokopoGateway;
    private DarajaPaymentGateway darajaGateway;
    private PlatformMpesaCustodySettingsService custodySettings;
    private PlatformKioskPaySettingsService kioskPaySettings;
    private PlatformDarajaSettingsService darajaSettings;
    private ObjectProvider<PlatformMpesaCustodySettingsService> custodySettingsProvider;
    private ObjectProvider<PlatformKioskPaySettingsService> kioskPaySettingsProvider;
    private ObjectProvider<PlatformDarajaSettingsService> darajaSettingsProvider;
    private ObjectProvider<PlatformCustodySettlementService> selfProvider;

    private PlatformCustodySettlementService service;

    private final Map<String, PlatformCustodySettlement> store = new HashMap<>();

    @BeforeEach
    void setUp() {
        settlementRepository = mock(PlatformCustodySettlementRepository.class);
        configRepository = mock(PaymentGatewayConfigRepository.class);
        kopokopoGateway = mock(KopokopoPaymentGateway.class);
        darajaGateway = mock(DarajaPaymentGateway.class);
        custodySettings = mock(PlatformMpesaCustodySettingsService.class);
        kioskPaySettings = mock(PlatformKioskPaySettingsService.class);
        darajaSettings = mock(PlatformDarajaSettingsService.class);
        custodySettingsProvider = mock(ObjectProvider.class);
        kioskPaySettingsProvider = mock(ObjectProvider.class);
        darajaSettingsProvider = mock(ObjectProvider.class);
        selfProvider = mock(ObjectProvider.class);

        service = new PlatformCustodySettlementService(
                settlementRepository,
                configRepository,
                kopokopoGateway,
                darajaGateway,
                custodySettingsProvider,
                kioskPaySettingsProvider,
                darajaSettingsProvider,
                new ObjectMapper(),
                selfProvider);
        ReflectionTestUtils.setField(service, "publicApiBaseUrl", "http://localhost:5050");

        when(selfProvider.getObject()).thenReturn(service);
        when(custodySettingsProvider.getIfAvailable()).thenReturn(custodySettings);
        when(kioskPaySettingsProvider.getIfAvailable()).thenReturn(kioskPaySettings);
        when(darajaSettingsProvider.getIfAvailable()).thenReturn(darajaSettings);
        when(kioskPaySettings.kopokopoCredentials()).thenReturn(Optional.of(CREDS));
        when(darajaSettings.credentials()).thenReturn(Optional.of(DARAJA_CREDS));

        store.clear();
        when(settlementRepository.save(any(PlatformCustodySettlement.class))).thenAnswer(inv -> {
            PlatformCustodySettlement s = inv.getArgument(0);
            if (s.getId() == null || s.getId().isBlank()) {
                s.setId("pcs-" + UUID.randomUUID());
            }
            store.put(s.getId(), s);
            return s;
        });
        when(settlementRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
    }

    // ── Collect → settle (same rail) ────────────────────────────────

    @Test
    void onStkConfirmed_custodyKopokopo_persistsAndSendsMoney() {
        when(configRepository.findById(CONFIG_ID)).thenReturn(Optional.of(custodyConfig()));
        when(settlementRepository.findByStkPushId("push-1")).thenReturn(Optional.empty());
        when(kopokopoGateway.sendMoney(any())).thenReturn(SendMoneyResult.accepted("sm-1"));

        service.onStkConfirmed(custodyPush(GatewayType.KOPOKOPO));

        assertThat(store).hasSize(1);
        PlatformCustodySettlement row = store.values().iterator().next();
        assertThat(row.getProvider()).isEqualTo(PlatformMpesaCustodyProviders.KOPOKOPO);
        assertThat(row.getStatus()).isEqualTo(PlatformCustodySettlementStatuses.SETTLING);
        assertThat(row.getDisbursementId()).isEqualTo("sm-1");
        assertThat(row.getDestinationTill()).isEqualTo("3502582");
        verify(kopokopoGateway).sendMoney(any());
    }

    @Test
    void onStkConfirmed_isIdempotentPerPush() {
        when(configRepository.findById(CONFIG_ID)).thenReturn(Optional.of(custodyConfig()));
        PlatformCustodySettlement existing = settlement("push-1", PlatformMpesaCustodyProviders.KOPOKOPO);
        when(settlementRepository.findByStkPushId("push-1")).thenReturn(Optional.of(existing));

        service.onStkConfirmed(custodyPush(GatewayType.KOPOKOPO));

        verify(settlementRepository, never()).save(any());
        verify(kopokopoGateway, never()).sendMoney(any());
    }

    @Test
    void onStkConfirmed_byoKopokopoConfig_isNotSettled() {
        PaymentGatewayConfig byo = custodyConfig();
        byo.setGatewayType(GatewayType.KOPOKOPO);
        when(configRepository.findById(CONFIG_ID)).thenReturn(Optional.of(byo));

        service.onStkConfirmed(custodyPush(GatewayType.KOPOKOPO));

        verify(settlementRepository, never()).save(any());
        verify(kopokopoGateway, never()).sendMoney(any());
    }

    @Test
    void onStkConfirmed_darajaRail_doesNotB2bSettle() {
        when(configRepository.findById(CONFIG_ID)).thenReturn(Optional.of(custodyConfig()));

        service.onStkConfirmed(custodyPush(GatewayType.DARAJA));

        verify(settlementRepository, never()).save(any());
        verify(darajaGateway, never()).sendB2B(any());
    }

    @Test
    void handleDarajaDisburseResult_settlesAndFails() {
        PlatformCustodySettlement ok = settlement("pcs-d1", PlatformMpesaCustodyProviders.DARAJA);
        ok.setDisbursementId("conv-ok");
        store.put(ok.getId(), ok);
        when(settlementRepository.findByDisbursementId("conv-ok")).thenReturn(Optional.of(ok));

        service.handleDarajaDisburseResult(
                new DarajaPaymentGateway.B2BResult(true, "conv-ok", "orig", "0", "Success"));
        assertThat(ok.getStatus()).isEqualTo(PlatformCustodySettlementStatuses.SETTLED);

        PlatformCustodySettlement bad = settlement("pcs-d2", PlatformMpesaCustodyProviders.DARAJA);
        bad.setDisbursementId("conv-bad");
        store.put(bad.getId(), bad);
        when(settlementRepository.findByDisbursementId("conv-bad")).thenReturn(Optional.of(bad));

        service.handleDarajaDisburseResult(
                new DarajaPaymentGateway.B2BResult(false, "conv-bad", "orig", "2001", "Insufficient balance"));
        assertThat(bad.getStatus()).isEqualTo(PlatformCustodySettlementStatuses.FAILED);
    }

    // ── Webhook matching ────────────────────────────────────────────

    @Test
    void handleSendMoneyWebhook_matchesOnCheckoutIdNotMpesaRef() {
        PlatformCustodySettlement row = settlement("pcs-1", PlatformMpesaCustodyProviders.KOPOKOPO);
        row.setStatus(PlatformCustodySettlementStatuses.SETTLING);
        row.setDisbursementId("sm-1");
        store.put(row.getId(), row);
        // The Send Money resource id is the stable key; transaction id is the M-Pesa ref.
        when(settlementRepository.findByDisbursementId("sm-1")).thenReturn(Optional.of(row));

        boolean handled = service.handleSendMoneyWebhook(
                new WebhookResult(null, "QCLREF", null, new BigDecimal("500.00"), null,
                        true, false, "sm-1", "evt-1", "send_money", null));

        assertThat(handled).isTrue();
        assertThat(row.getStatus()).isEqualTo(PlatformCustodySettlementStatuses.SETTLED);
        assertThat(row.getSettledAt()).isNotNull();
    }

    @Test
    void handleSendMoneyWebhook_terminalFailureAndFloat_pausesSendMoney() {
        PlatformCustodySettlement row = settlement("pcs-2", PlatformMpesaCustodyProviders.KOPOKOPO);
        row.setStatus(PlatformCustodySettlementStatuses.SETTLING);
        row.setDisbursementId("sm-2");
        store.put(row.getId(), row);
        when(settlementRepository.findByDisbursementId("sm-2")).thenReturn(Optional.of(row));

        boolean handled = service.handleSendMoneyWebhook(
                new WebhookResult(null, "QCLREF", null, new BigDecimal("500.00"), null,
                        false, true, "sm-2", "evt-2", "send_money", null,
                        "Transfer amount exceeds amount available to move"));

        assertThat(handled).isTrue();
        assertThat(row.getStatus()).isEqualTo(PlatformCustodySettlementStatuses.FAILED);
        verify(kioskPaySettings).markSendMoneyFloatConstrained(any());
    }

    @Test
    void handleSendMoneyWebhook_ignoresNonKopokopoProvider() {
        PlatformCustodySettlement row = settlement("pcs-3", PlatformMpesaCustodyProviders.DARAJA);
        row.setStatus(PlatformCustodySettlementStatuses.SETTLING);
        row.setDisbursementId("sm-3");
        store.put(row.getId(), row);
        when(settlementRepository.findByDisbursementId("sm-3")).thenReturn(Optional.of(row));

        boolean handled = service.handleSendMoneyWebhook(
                new WebhookResult(null, "QCLREF", null, new BigDecimal("500.00"), null,
                        true, false, "sm-3", "evt-3", "send_money", null));

        assertThat(handled).isFalse();
        assertThat(row.getStatus()).isEqualTo(PlatformCustodySettlementStatuses.SETTLING);
    }

    // ── Reconcile ───────────────────────────────────────────────────

    @Test
    void reconcileInFlight_pollsProviderAndSettles() {
        PlatformCustodySettlement row = settlement("pcs-4", PlatformMpesaCustodyProviders.KOPOKOPO);
        row.setStatus(PlatformCustodySettlementStatuses.SETTLING);
        row.setDisbursementId("sm-4");
        store.put(row.getId(), row);
        when(settlementRepository.findByStatusAndCreatedAtBefore(
                eq(PlatformCustodySettlementStatuses.SETTLING), any()))
                .thenReturn(List.of(row));
        when(kopokopoGateway.querySendMoneyStatus("sm-4", CREDS)).thenReturn(
                new WebhookResult(null, "QCLREF", null, new BigDecimal("500.00"), null,
                        true, false, "sm-4", "evt-4", "send_money", null));

        service.reconcileInFlight();

        assertThat(row.getStatus()).isEqualTo(PlatformCustodySettlementStatuses.SETTLED);
    }

    @Test
    void reconcileInFlight_skipsWithoutProviderId() {
        PlatformCustodySettlement row = settlement("pcs-5", PlatformMpesaCustodyProviders.KOPOKOPO);
        row.setStatus(PlatformCustodySettlementStatuses.SETTLING);
        row.setDisbursementId(null);
        store.put(row.getId(), row);
        when(settlementRepository.findByStatusAndCreatedAtBefore(
                eq(PlatformCustodySettlementStatuses.SETTLING), any()))
                .thenReturn(List.of(row));

        service.reconcileInFlight();

        verify(kopokopoGateway, never()).querySendMoneyStatus(any(), any());
        assertThat(row.getStatus()).isEqualTo(PlatformCustodySettlementStatuses.SETTLING);
    }

    @Test
    void reconcilePending_reSendsUnacceptedSettlement() {
        PlatformCustodySettlement row = settlement("push-9", PlatformMpesaCustodyProviders.KOPOKOPO);
        row.setStatus(PlatformCustodySettlementStatuses.PENDING);
        store.put(row.getId(), row);
        when(settlementRepository.findByStatusAndCreatedAtBefore(
                eq(PlatformCustodySettlementStatuses.PENDING), any()))
                .thenReturn(List.of(row));
        when(kopokopoGateway.sendMoney(any())).thenReturn(SendMoneyResult.accepted("sm-9"));

        service.reconcilePending();

        assertThat(row.getStatus()).isEqualTo(PlatformCustodySettlementStatuses.SETTLING);
        assertThat(row.getDisbursementId()).isEqualTo("sm-9");
    }

    // ── Ops retry ───────────────────────────────────────────────────

    @Test
    void retry_failedSettlement_resendsOnSameRail() {
        PlatformCustodySettlement row = settlement("pcs-6", PlatformMpesaCustodyProviders.KOPOKOPO);
        row.setStatus(PlatformCustodySettlementStatuses.FAILED);
        row.setDisbursementId("sm-old");
        row.setFailureReason("declined");
        store.put(row.getId(), row);
        when(kopokopoGateway.sendMoney(any())).thenReturn(SendMoneyResult.accepted("sm-new"));

        service.retry(row.getId());

        assertThat(row.getStatus()).isEqualTo(PlatformCustodySettlementStatuses.SETTLING);
        assertThat(row.getDisbursementId()).isEqualTo("sm-new");
        assertThat(row.getFailureReason()).isNull();
    }

    @Test
    void retry_settledSettlement_isRejected() {
        PlatformCustodySettlement row = settlement("pcs-7", PlatformMpesaCustodyProviders.KOPOKOPO);
        row.setStatus(PlatformCustodySettlementStatuses.SETTLED);
        store.put(row.getId(), row);

        assertThatThrownBy(() -> service.retry(row.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(kopokopoGateway, never()).sendMoney(any());
    }

    // ── Destination validation ──────────────────────────────────────

    @Test
    void validateDestination_tillAndPaybill() {
        assertThat(service.validateDestinationJson("{\"type\":\"till\",\"tillNumber\":\"3502582\"}"))
                .isNull();
        assertThat(service.validateDestinationJson(
                "{\"type\":\"paybill\",\"businessNumber\":\"247247\",\"accountNumber\":\"INV-1\"}"))
                .isNull();
        assertThat(service.validateDestinationJson("{\"type\":\"till\",\"tillNumber\":\"12\"}"))
                .isNotNull();
        assertThat(service.validateDestinationJson(
                "{\"type\":\"paybill\",\"businessNumber\":\"247247\"}"))
                .isNotNull();
        assertThat(service.validateDestinationJson(null)).isNotNull();
    }

    // ── Custody rail health check (tenant “Test” button) ────────────

    @Test
    void testActiveRail_offFails() {
        when(custodySettings.activeProvider()).thenReturn(PlatformMpesaCustodyProviders.OFF);

        PlatformCustodySettlementService.RailTestResult res = service.testActiveRail();

        assertThat(res.ok()).isFalse();
        assertThat(res.code()).isEqualTo("CUSTODY_OFF");
    }

    @Test
    void testActiveRail_darajaNotConfiguredFails() {
        when(custodySettings.activeProvider()).thenReturn(PlatformMpesaCustodyProviders.DARAJA);
        when(darajaSettings.isEnabledAndConfigured()).thenReturn(false);

        PlatformCustodySettlementService.RailTestResult res = service.testActiveRail();

        assertThat(res.ok()).isFalse();
        assertThat(res.code()).isEqualTo("DARAJA_NOT_READY");
    }

    @Test
    void testActiveRail_missingCredentialsFails() {
        when(custodySettings.activeProvider()).thenReturn(PlatformMpesaCustodyProviders.KOPOKOPO);
        when(kioskPaySettings.kopokopoCredentials()).thenReturn(Optional.empty());

        PlatformCustodySettlementService.RailTestResult res = service.testActiveRail();

        assertThat(res.ok()).isFalse();
        assertThat(res.code()).isEqualTo("NO_CREDENTIALS");
    }

    @Test
    void testActiveRail_kopokopoReachable() {
        when(custodySettings.activeProvider()).thenReturn(PlatformMpesaCustodyProviders.KOPOKOPO);
        when(kopokopoGateway.validateConfiguration(any())).thenReturn(ValidationResult.success());

        PlatformCustodySettlementService.RailTestResult res = service.testActiveRail();

        assertThat(res.ok()).isTrue();
        assertThat(res.code()).isNull();
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static GatewayStkPush custodyPush(GatewayType gatewayType) {
        GatewayStkPush push = new GatewayStkPush();
        push.setId("push-1");
        push.setBusinessId(BUSINESS);
        push.setConfigId(CONFIG_ID);
        push.setGatewayType(gatewayType);
        push.setContextType(StkPushContextType.POS_PAYMENT);
        push.setAmount(new BigDecimal("500.00"));
        return push;
    }

    private static PaymentGatewayConfig custodyConfig() {
        PaymentGatewayConfig cfg = new PaymentGatewayConfig();
        cfg.setId(CONFIG_ID);
        cfg.setBusinessId(BUSINESS);
        cfg.setGatewayType(GatewayType.CUSTODY_MPESA);
        cfg.setStatus(GatewayStatus.ACTIVE);
        cfg.setDisplayInstructionsJson("{\"type\":\"till\",\"tillNumber\":\"3502582\"}");
        return cfg;
    }

    private static PlatformCustodySettlement settlement(String id, String provider) {
        PlatformCustodySettlement row = new PlatformCustodySettlement();
        row.setId(id);
        row.setBusinessId(BUSINESS);
        row.setGatewayConfigId(CONFIG_ID);
        row.setStkPushId("push-" + id);
        row.setProvider(provider);
        row.setAmount(new BigDecimal("500.00"));
        row.setCurrency("KES");
        row.setDestinationType("till");
        row.setDestinationTill("3502582");
        row.setStatus(PlatformCustodySettlementStatuses.SETTLING);
        row.setCreatedAt(Instant.now().minusSeconds(3600));
        return row;
    }
}
