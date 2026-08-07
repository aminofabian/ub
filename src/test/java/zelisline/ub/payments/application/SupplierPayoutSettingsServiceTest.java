package zelisline.ub.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.payments.api.dto.SupplierPayoutSettingsRequest;
import zelisline.ub.payments.domain.GatewayStatus;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.PlatformPaymentGateway;
import zelisline.ub.payments.domain.SupplierPayoutSettings;
import zelisline.ub.payments.repository.PaymentGatewayConfigRepository;
import zelisline.ub.payments.repository.PlatformPaymentGatewayRepository;
import zelisline.ub.payments.repository.SupplierPayoutSettingsRepository;

@ExtendWith(MockitoExtension.class)
class SupplierPayoutSettingsServiceTest {

    @Mock
    private SupplierPayoutSettingsRepository settingsRepository;
    @Mock
    private PaymentGatewayConfigRepository configRepository;
    @Mock
    private PlatformPaymentGatewayRepository platformGatewayRepository;

    private SupplierPayoutSettingsService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new SupplierPayoutSettingsService(
                settingsRepository, configRepository, platformGatewayRepository, objectMapper);
        ReflectionTestUtils.setField(service, "autoPayZone", "Africa/Nairobi");
    }

    @Test
    void rejectsAutoPayWhenPayoutsDisabled() {
        when(settingsRepository.findById("biz-1"))
                .thenReturn(Optional.of(SupplierPayoutSettings.disabledFor("biz-1")));

        assertThatThrownBy(() -> service.updateSettings(
                        "biz-1",
                        new SupplierPayoutSettingsRequest(null, null, true, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Enable supplier payouts");
    }

    @Test
    void enablesAutoPayWhenPayoutsAndGatewayReady() {
        SupplierPayoutSettings existing = SupplierPayoutSettings.disabledFor("biz-1");
        existing.setEnabled(true);
        existing.setPaymentGatewayConfigId("cfg-1");

        PaymentGatewayConfig cfg = activeKopokopoConfig();
        PlatformPaymentGateway platform = platformKopokopo();

        when(settingsRepository.findById("biz-1")).thenReturn(Optional.of(existing));
        when(configRepository.findById("cfg-1")).thenReturn(Optional.of(cfg));
        when(platformGatewayRepository.findById(GatewayType.KOPOKOPO)).thenReturn(Optional.of(platform));
        when(configRepository.findByBusinessId("biz-1")).thenReturn(List.of(cfg));
        when(settingsRepository.save(any(SupplierPayoutSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.updateSettings(
                "biz-1",
                new SupplierPayoutSettingsRequest(null, null, true, List.of("09:30", "21:00")));

        assertThat(response.autoPayEnabled()).isTrue();
        assertThat(response.enabled()).isTrue();
        assertThat(response.autoPayTimes()).containsExactly("09:30", "21:00");
        ArgumentCaptor<SupplierPayoutSettings> cap = ArgumentCaptor.forClass(SupplierPayoutSettings.class);
        verify(settingsRepository).save(cap.capture());
        assertThat(cap.getValue().isAutoPayEnabled()).isTrue();
        assertThat(cap.getValue().getAutoPayTimesJson()).contains("09:30");
    }

    @Test
    void rejectsInvalidAutoPayTimes() {
        SupplierPayoutSettings existing = SupplierPayoutSettings.disabledFor("biz-1");
        existing.setEnabled(true);
        existing.setPaymentGatewayConfigId("cfg-1");

        PaymentGatewayConfig cfg = activeKopokopoConfig();
        PlatformPaymentGateway platform = platformKopokopo();

        when(settingsRepository.findById("biz-1")).thenReturn(Optional.of(existing));
        when(configRepository.findById("cfg-1")).thenReturn(Optional.of(cfg));
        when(platformGatewayRepository.findById(GatewayType.KOPOKOPO)).thenReturn(Optional.of(platform));

        assertThatThrownBy(() -> service.updateSettings(
                        "biz-1",
                        new SupplierPayoutSettingsRequest(null, null, null, List.of("25:99"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("HH:mm");
    }

    @Test
    void disablingPayoutsClearsAutoPay() {
        SupplierPayoutSettings existing = SupplierPayoutSettings.disabledFor("biz-1");
        existing.setEnabled(true);
        existing.setAutoPayEnabled(true);
        existing.setPaymentGatewayConfigId("cfg-1");

        when(settingsRepository.findById("biz-1")).thenReturn(Optional.of(existing));
        when(configRepository.findByBusinessId("biz-1")).thenReturn(List.of());
        when(settingsRepository.save(any(SupplierPayoutSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.updateSettings(
                "biz-1",
                new SupplierPayoutSettingsRequest(false, null, null, null));

        assertThat(response.enabled()).isFalse();
        assertThat(response.autoPayEnabled()).isFalse();
        ArgumentCaptor<SupplierPayoutSettings> cap = ArgumentCaptor.forClass(SupplierPayoutSettings.class);
        verify(settingsRepository).save(cap.capture());
        assertThat(cap.getValue().isAutoPayEnabled()).isFalse();
        assertThat(cap.getValue().getPaymentGatewayConfigId()).isNull();
    }

    private static PaymentGatewayConfig activeKopokopoConfig() {
        PaymentGatewayConfig cfg = new PaymentGatewayConfig();
        cfg.setId("cfg-1");
        cfg.setBusinessId("biz-1");
        cfg.setGatewayType(GatewayType.KOPOKOPO);
        cfg.setStatus(GatewayStatus.ACTIVE);
        cfg.setLabel("KopoKopo");
        return cfg;
    }

    private static PlatformPaymentGateway platformKopokopo() {
        PlatformPaymentGateway platform = new PlatformPaymentGateway();
        platform.setGatewayType(GatewayType.KOPOKOPO);
        platform.setEnabled(true);
        platform.setSupplierPayoutSupported(true);
        return platform;
    }
}
