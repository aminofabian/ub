package zelisline.ub.platform.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "platform_integration_settings")
@Getter
@Setter
public class PlatformIntegrationSettings {

    public static final String SINGLETON_ID = "00000000-0000-0000-0000-000000000001";

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "deepseek_api_key_enc", columnDefinition = "TEXT")
    private String deepseekApiKeyEnc;

    @Column(name = "deepseek_host", length = 255)
    private String deepseekHost;

    @Column(name = "deepseek_url", length = 512)
    private String deepseekUrl;

    @Column(name = "deepseek_model", length = 128)
    private String deepseekModel;

    @Column(name = "rapidapi_whatsapp_key_enc", columnDefinition = "TEXT")
    private String rapidapiWhatsappKeyEnc;

    @Column(name = "rapidapi_whatsapp_host", length = 255)
    private String rapidapiWhatsappHost;

    @Column(name = "rapidapi_whatsapp_lookup_url", length = 512)
    private String rapidapiWhatsappLookupUrl;

    @Column(name = "rapidapi_whatsapp_phone_field", length = 64)
    private String rapidapiWhatsappPhoneField;

    @Column(name = "rapidapi_whatsapp_phone_digits_only")
    private Boolean rapidapiWhatsappPhoneDigitsOnly;

    @Column(name = "sms_provider", length = 32)
    private String smsProvider;

    @Column(name = "sozuri_project", length = 128)
    private String sozuriProject;

    @Column(name = "sozuri_api_key_enc", columnDefinition = "TEXT")
    private String sozuriApiKeyEnc;

    @Column(name = "sozuri_from", length = 64)
    private String sozuriFrom;

    @Column(name = "sozuri_type", length = 32)
    private String sozuriType;

    @Column(name = "sozuri_api_url", length = 512)
    private String sozuriApiUrl;

    @Column(name = "textsms_partner_id", length = 64)
    private String textsmsPartnerId;

    @Column(name = "textsms_api_key_enc", columnDefinition = "TEXT")
    private String textsmsApiKeyEnc;

    @Column(name = "textsms_shortcode", length = 64)
    private String textsmsShortcode;

    @Column(name = "textsms_api_url", length = 512)
    private String textsmsApiUrl;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
