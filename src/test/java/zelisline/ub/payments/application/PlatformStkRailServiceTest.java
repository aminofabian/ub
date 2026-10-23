package zelisline.ub.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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
 * Package/SMS/domain charges collect on the rail Super Admin made live: the Custody
 * rail first, then the Palmart KopoKopo till. Nothing may be pinned to KopoKopo in
 * code — that is what produced the {@code invalid_client} OAuth failures.
 */
@SuppressWarnings("unchecked")
class PlatformStkRailServiceTest {

    private ObjectProvider<PlatformCustodySettlementService> custodyProvider;
    private ObjectProvider<PlatformDomainSettingsService> domainProvider;
    private PlatformCustodySettlementService custody;
    private PlatformDomainSettingsService domains;

    private PlatformStkRailService service;

    @BeforeEach
    void setUp() {
        custodyProvider = mock(ObjectProvider.class);
        domainProvider = mock(ObjectProvider.class);
        custody = mock(PlatformCustodySettlementService.class);
        domains = mock(PlatformDomainSettingsService.class);
        when(custodyProvider.getIfAvailable()).thenReturn(custody);
        when(domainProvider.getIfAvailable()).thenReturn(domains);
        when(custody.activeProvider()).thenReturn(PlatformMpesaCustodyProviders.OFF);
        when(custody.resolveCollectRail()).thenReturn(Optional.empty());

        service = new PlatformStkRailService(custodyProvider, domainProvider);
    }

    @Test
    void resolve_prefersCustodyDarajaRail() {
        when(custody.activeProvider()).thenReturn(PlatformMpesaCustodyProviders.DARAJA);
        when(custody.resolveCollectRail()).thenReturn(Optional.of(
                new PlatformCustodySettlementService.CollectRail(
                        GatewayType.DARAJA,
                        "DARAJA",
                        Map.of("shortcode", "4030000", "passkey", "pk", "environment", "production"))));

        PlatformStkRailService.Rail rail = service.require("package renewal");

        assertThat(rail.gatewayType()).isEqualTo(GatewayType.DARAJA);
        assertThat(rail.configId()).isEqualTo(PlatformDarajaSettings.PLATFORM_DARAJA_CONFIG_ID);
        assertThat(rail.label()).contains("Custody rail");
    }

    @Test
    void resolve_legacyKopokopoCustodyUsesKioskPayConfigId() {
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
    void resolve_fallsBackToPalmartKopokopoTillWhenCustodyRailIsOff() {
        when(domains.palmartStkConfigured()).thenReturn(true);
        when(domains.resolvePalmartStkCredentials())
                .thenReturn(Map.of("clientId", "c", "clientSecret", "s", "tillNumber", "123456"));

        PlatformStkRailService.Rail rail = service.require("package renewal");

        assertThat(rail.gatewayType()).isEqualTo(GatewayType.KOPOKOPO);
        assertThat(rail.configId()).isEqualTo(PlatformDomainSettingsService.PLATFORM_DOMAIN_STK_CONFIG_ID);
    }

    @Test
    void resolve_neverSilentlyHopsRailsWhenCustodyProviderIsNotReady() {
        when(custody.activeProvider()).thenReturn(PlatformMpesaCustodyProviders.DARAJA);
        when(custody.resolveCollectRail()).thenReturn(Optional.empty());
        when(domains.palmartStkConfigured()).thenReturn(true);

        assertThatThrownBy(() -> service.require("package renewal"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
                .hasMessageContaining("Custody rail")
                .hasMessageContaining("not ready");
    }

    @Test
    void require_failsWithActionableMessageWhenNoRailIsConfigured() {
        when(domains.palmartStkConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.require("package renewal"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
                .hasMessageContaining("Custody rail");
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
