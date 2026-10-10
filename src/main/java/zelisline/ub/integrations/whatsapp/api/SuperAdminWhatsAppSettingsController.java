package zelisline.ub.integrations.whatsapp.api;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import zelisline.ub.integrations.whatsapp.api.dto.WhatsAppChannelDtos.SettingsResponse;
import zelisline.ub.integrations.whatsapp.api.dto.WhatsAppChannelDtos.UpdateSettingsRequest;
import zelisline.ub.integrations.whatsapp.application.WhatsAppChannelSettingsService;
import zelisline.ub.platform.application.PlatformIntegrationSettingsService;

/**
 * Super-admin on/off switches for the WhatsApp channel (inbound + outbound), persisted so they
 * survive a restart. Authorized by the {@code /api/v1/super-admin/**} rule in {@code SecurityConfig}.
 */
@Validated
@RestController
@RequestMapping("/api/v1/super-admin/whatsapp/settings")
@RequiredArgsConstructor
public class SuperAdminWhatsAppSettingsController {

    private final WhatsAppChannelSettingsService channelSettings;
    private final PlatformIntegrationSettingsService platformIntegrationSettingsService;

    @GetMapping
    public SettingsResponse get() {
        return toResponse(channelSettings.flags());
    }

    @PutMapping
    public SettingsResponse update(@Valid @RequestBody UpdateSettingsRequest body) {
        return toResponse(channelSettings.update(body.inboundEnabled(), body.outboundEnabled()));
    }

    private SettingsResponse toResponse(WhatsAppChannelSettingsService.Flags flags) {
        boolean metaConfigured = platformIntegrationSettingsService.resolveMetaWhatsApp().configured();
        return new SettingsResponse(
                flags.inboundEnabled(),
                flags.outboundEnabled(),
                flags.inboundEnvDefault(),
                flags.outboundEnvDefault(),
                metaConfigured);
    }
}
