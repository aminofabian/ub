package zelisline.ub.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.payments.domain.GatewayStatus;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.PlatformPaymentGateway;
import zelisline.ub.payments.domain.spi.PaymentGateway;
import zelisline.ub.payments.domain.spi.StkPushResponse;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.payments.repository.PaymentGatewayConfigRepository;

/**
 * STK rail priority: BYO (Model A) always beats till-only custody (Model B); custody
 * collects on the platform rail with platform credentials.
 */
@SuppressWarnings("unchecked")
class PaymentGatewayStkServiceTest {

    private static final String BUSINESS = "b1";

    private PaymentGatewayConfigRepository configRepository;
    private PlatformPaymentGatewayService platformPaymentGatewayService;
    private PaymentGatewayRegistry gatewayRegistry;
    private CredentialEncryptionService encryptionService;
    private ObjectProvider<PlatformDarajaSettingsService> darajaProvider;
    private ObjectProvider<PlatformCustodySettlementService> custodyProvider;
    private PlatformCustodySettlementService custodyService;
    private PaymentGateway kopokopoGateway;

    private PaymentGatewayStkService service;

    @BeforeEach
    void setUp() {
        configRepository = mock(PaymentGatewayConfigRepository.class);
        platformPaymentGatewayService = mock(PlatformPaymentGatewayService.class);
        gatewayRegistry = mock(PaymentGatewayRegistry.class);
        encryptionService = mock(CredentialEncryptionService.class);
        darajaProvider = mock(ObjectProvider.class);
        custodyProvider = mock(ObjectProvider.class);
        custodyService = mock(PlatformCustodySettlementService.class);
        kopokopoGateway = mock(PaymentGateway.class);

        service = new PaymentGatewayStkService(
                configRepository,
                platformPaymentGatewayService,
                gatewayRegistry,
                encryptionService,
                new ObjectMapper(),
                darajaProvider,
                custodyProvider);
        ReflectionTestUtils.setField(service, "publicApiBaseUrl", "http://localhost:5050");

        when(custodyProvider.getIfAvailable()).thenReturn(custodyService);
        when(gatewayRegistry.has("KOPOKOPO")).thenReturn(true);
        when(gatewayRegistry.get("KOPOKOPO")).thenReturn(kopokopoGateway);
        when(kopokopoGateway.initiateStkPush(any())).thenReturn(
                StkPushResponse.accepted("checkout-1", "mr-1", "0", "Success"));
    }

    @Test
    void findDefaultActiveConfig_prefersByoOverCustody() {
        when(platformPaymentGatewayService.listEnabled())
                .thenReturn(List.of(platformGateway(GatewayType.KOPOKOPO)));
        when(configRepository.findByBusinessIdAndGatewayTypeAndStatus(
                BUSINESS, GatewayType.KOPOKOPO, GatewayStatus.ACTIVE))
                .thenReturn(List.of(byoConfig()));

        assertThat(service.findDefaultActiveStkConfigId(BUSINESS)).isEqualTo("cfg-byo");
        verify(custodyService, never()).findActiveCustodyConfig(anyString());
    }

    @Test
    void findDefaultActiveConfig_fallsBackToCustodyWhenNoByo() {
        when(platformPaymentGatewayService.listEnabled()).thenReturn(List.of());
        when(custodyService.platformRailsReady()).thenReturn(true);
        when(custodyService.findActiveCustodyConfig(BUSINESS)).thenReturn(custodyConfig());

        assertThat(service.findDefaultActiveStkConfigId(BUSINESS)).isEqualTo("cfg-custody");
    }

    @Test
    void initiate_byoAccepted_neverTouchesCustodyRail() {
        when(platformPaymentGatewayService.listEnabled())
                .thenReturn(List.of(platformGateway(GatewayType.KOPOKOPO)));
        when(configRepository.findByBusinessIdAndGatewayTypeAndStatus(
                BUSINESS, GatewayType.KOPOKOPO, GatewayStatus.ACTIVE))
                .thenReturn(List.of(byoConfig()));
        when(configRepository.findById("cfg-byo")).thenReturn(Optional.of(byoConfig()));
        when(encryptionService.decrypt("enc")).thenReturn("{\"clientId\":\"c\",\"tillNumber\":\"123456\"}");

        PaymentGatewayStkService.StkPushOutcome outcome = service.initiate(
                BUSINESS, null, "254712345678", new BigDecimal("500.00"), "REF-1", "Test");

        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.configId()).isEqualTo("cfg-byo");
        assertThat(outcome.gatewayType()).isEqualTo(GatewayType.KOPOKOPO.name());
        verify(custodyService, never()).resolveCollectRail();
    }

    @Test
    void initiate_noByo_collectsViaCustodyPlatformCredentials() {
        when(platformPaymentGatewayService.listEnabled()).thenReturn(List.of());
        when(custodyService.platformRailsReady()).thenReturn(true);
        when(custodyService.findActiveCustodyConfig(BUSINESS)).thenReturn(custodyConfig());
        when(custodyService.resolveCollectRail()).thenReturn(Optional.of(
                new PlatformCustodySettlementService.CollectRail(
                        GatewayType.KOPOKOPO, "KOPOKOPO",
                        Map.of("clientId", "c", "tillNumber", "123456"))));

        PaymentGatewayStkService.StkPushOutcome outcome = service.initiate(
                BUSINESS, null, "254712345678", new BigDecimal("500.00"), "REF-1", "Test");

        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.configId()).isEqualTo("cfg-custody");
        assertThat(outcome.gatewayType()).isEqualTo(GatewayType.KOPOKOPO.name());
    }

    private static PlatformPaymentGateway platformGateway(GatewayType type) {
        PlatformPaymentGateway pg = new PlatformPaymentGateway();
        pg.setGatewayType(type);
        return pg;
    }

    private static PaymentGatewayConfig byoConfig() {
        PaymentGatewayConfig cfg = new PaymentGatewayConfig();
        cfg.setId("cfg-byo");
        cfg.setBusinessId(BUSINESS);
        cfg.setGatewayType(GatewayType.KOPOKOPO);
        cfg.setStatus(GatewayStatus.ACTIVE);
        cfg.setCredentialsJson("enc");
        return cfg;
    }

    private static PaymentGatewayConfig custodyConfig() {
        PaymentGatewayConfig cfg = new PaymentGatewayConfig();
        cfg.setId("cfg-custody");
        cfg.setBusinessId(BUSINESS);
        cfg.setGatewayType(GatewayType.CUSTODY_MPESA);
        cfg.setStatus(GatewayStatus.ACTIVE);
        cfg.setDisplayInstructionsJson("{\"type\":\"till\",\"tillNumber\":\"3502582\"}");
        return cfg;
    }
}
