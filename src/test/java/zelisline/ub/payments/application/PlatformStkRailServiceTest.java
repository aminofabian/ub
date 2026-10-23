package zelisline.ub.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PlatformDarajaSettings;
import zelisline.ub.payments.domain.PlatformKioskPaySettings;
import zelisline.ub.payments.domain.PlatformMpesaCustodyProviders;
import zelisline.ub.platform.application.PlatformDomainSettingsService;

/**
 * Package revenue must collect on the platform M-Pesa Daraja account Super Admin
 * enabled — the same rail tenant payments use — and never fall through to the
 * KopoKopo till when Daraja (or an explicitly selected custody Daraja rail) is the
 * configured provider. That fallthrough produced {@code invalid_client} OAuth errors.
 */
@SuppressWarnings("unchecked")
class PlatformStkRailServiceTest {

    private ObjectProvider<PlatformDarajaSettingsService> darajaProvider;
    private ObjectProvider<PlatformCustodySettlementService> custodyProvider;
    private ObjectProvider<PlatformDomainSettingsService> domainProvider;
    private PlatformDarajaSettingsService daraja;
    private PlatformCustodySettlementService custody;
    private PlatformDomainSettingsService domains;

    private PlatformStkRailService service;

    @BeforeEach
    void setUp() {
        darajaProvider = mock(ObjectProvider.class);
        custodyProvider = mock(ObjectProvider.class);
        domainProvider = mock(ObjectProvider.class);
        daraja = mock(PlatformDarajaSettingsService.class);
        custody = mock(PlatformCustodySettlementService.class);
        domains = mock(PlatformDomainSettingsService.class);

        when(darajaProvider.getIfAvailable()).thenReturn(daraja);
        when(custodyProvider.getIfAvailable()).thenReturn(custody);
        when(domainProvider.getIfAvailable()).thenReturn(domains);
        when(daraja.isEnabledAndConfigured()).thenReturn(false);
        when(custody.activeProvider()).thenReturn(PlatformMpesaCustodyProviders.OFF);
        when(custody.resolveCollectRail()).thenReturn(Optional.empty());
        when(domains.palmartStkConfigured()).thenReturn(false);

        service = new PlatformStkRailService(darajaProvider, custodyProvider, domainProvider);
    }

    @Test
    void darajaRailWinsWhenEnabledEvenIfCustodyRailIsOff() {
        when(daraja.isEnabledAndConfigured()).thenReturn(true);
        when(daraja.credentials()).thenReturn(Optional.of(Map.of(
                "shortcode", "4030000",
                "passkey", "pk",
                "consumerKey", "ck",
                "consumerSecret", "cs",
                "environment", "production",
                "shortcodeType", "paybill")));

        PlatformStkRailService.Rail rail = service.require("package renewal");

        assertThat(rail.gatewayType()).isEqualTo(GatewayType.DARAJA);
        assertThat(rail.configId()).isEqualTo(PlatformDarajaSettings.PLATFORM_DARAJA_CONFIG_ID);
        assertThat(rail.credentials()).containsEntry("shortcode", "4030000");
    }

    @Test
    void custodyDarajaWithoutDarajaCredentialsNeverFallsBackToKopokopo() {
        when(custody.activeProvider()).thenReturn(PlatformMpesaCustodyProviders.DARAJA);
        when(domains.palmartStkConfigured()).thenReturn(true);

        assertThatThrownBy(() -> service.require("package renewal"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
                .hasMessageContaining("Safaricom Daraja");

        verify(domains, never()).resolvePalmartStkCredentials();
    }

    @Test
    void legacyKopokopoCustodyUsesKioskPayConfigId() {
        when(custody.activeProvider()).thenReturn(PlatformMpesaCustodyProviders.KOPOKOPO);
        when(custody.resolveCollectRail()).thenReturn(Optional.of(
                new PlatformCustodySettlementService.CollectRail(
                        GatewayType.KOPOKOPO,
                        "KOPOKOPO",
                        Map.of("clientId", "c", "clientSecret", "s", "tillNumber", "123456"))));

        PlatformStkRailService.Rail rail = service.require("package renewal");

        assertThat(rail.gatewayType()).isEqualTo(GatewayType.KOPOKOPO);
        assertThat(rail.configId()).isEqualTo(PlatformKioskPaySettings.PLATFORM_KOPOKOPO_CONFIG_ID);
    }

    @Test
    void fallsBackToPalmartKopokopoTillOnlyWhenDarajaIsOff() {
        when(domains.palmartStkConfigured()).thenReturn(true);
        when(domains.resolvePalmartStkCredentials())
                .thenReturn(Map.of("clientId", "c", "clientSecret", "s", "tillNumber", "123456"));

        PlatformStkRailService.Rail rail = service.require("package renewal");

        assertThat(rail.gatewayType()).isEqualTo(GatewayType.KOPOKOPO);
        assertThat(rail.configId()).isEqualTo(PlatformDomainSettingsService.PLATFORM_DOMAIN_STK_CONFIG_ID);
    }

    @Test
    void require_failsWithActionableMessageWhenNoRailIsConfigured() {
        assertThatThrownBy(() -> service.require("package renewal"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
                .hasMessageContaining("Safaricom Daraja");
    }

    @Test
    void darajaCredentialsCarryAShortAccountReference() {
        PlatformStkRailService.Rail rail = new PlatformStkRailService.Rail(
                GatewayType.DARAJA,
                PlatformDarajaSettings.PLATFORM_DARAJA_CONFIG_ID,
                "Safaricom Daraja",
                Map.of("shortcode", "4030000"));

        Map<String, String> creds = rail.credentialsFor("sub-0123456789ab-cd12ef");

        assertThat(creds.get("accountReference")).isEqualTo("sub012345678");
        assertThat(creds.get("accountReference")).hasSize(PlatformStkRailService.DARAJA_ACCOUNT_REFERENCE_MAX);
        assertThat(rail.credentials()).doesNotContainKey("accountReference");
    }

    @Test
    void kopokopoCredentialsArePassedThroughUnchanged() {
        PlatformStkRailService.Rail rail = new PlatformStkRailService.Rail(
                GatewayType.KOPOKOPO,
                PlatformDomainSettingsService.PLATFORM_DOMAIN_STK_CONFIG_ID,
                "Palmart KopoKopo till",
                Map.of("clientId", "c"));

        assertThat(rail.credentialsFor("sub-0123456789ab-cd12ef")).isEqualTo(Map.of("clientId", "c"));
    }
}
