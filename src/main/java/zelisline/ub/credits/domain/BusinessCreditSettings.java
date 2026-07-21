package zelisline.ub.credits.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "business_credit_settings")
public class BusinessCreditSettings {

    @Id
    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "loyalty_points_per_kes", nullable = false, precision = 14, scale = 8)
    private BigDecimal loyaltyPointsPerKes = BigDecimal.ZERO;

    @Column(name = "loyalty_kes_per_point", nullable = false, precision = 14, scale = 8)
    private BigDecimal loyaltyKesPerPoint = new BigDecimal("0.01");

    @Column(name = "loyalty_max_redeem_bps", nullable = false)
    private int loyaltyMaxRedeemBps = 5000;

    @Column(name = "credit_sale_reminder_enabled", nullable = false)
    private boolean creditSaleReminderEnabled;

    @Column(name = "credit_sale_reminder_payment_url", length = 512)
    private String creditSaleReminderPaymentUrl;

    @Column(name = "rapidapi_key_enc", columnDefinition = "TEXT")
    private String rapidapiKeyEnc;

    @Column(name = "rapidapi_host", length = 255)
    private String rapidapiHost;

    @Column(name = "rapidapi_lookup_url", length = 512)
    private String rapidapiLookupUrl;

    @Column(name = "rapidapi_phone_field", length = 64)
    private String rapidapiPhoneField;

    @Column(name = "rapidapi_phone_digits_only")
    private Boolean rapidapiPhoneDigitsOnly;

    @Column(name = "whatsapp_meta_access_token_enc", columnDefinition = "TEXT")
    private String whatsappMetaAccessTokenEnc;

    @Column(name = "whatsapp_meta_phone_number_id", length = 64)
    private String whatsappMetaPhoneNumberId;

    @Column(name = "whatsapp_meta_graph_version", nullable = false, length = 16)
    private String whatsappMetaGraphVersion = "v25.0";

    @Column(name = "sms_provider", nullable = false, length = 32)
    private String smsProvider = "none";

    @Column(name = "sms_africas_talking_username", length = 128)
    private String smsAfricasTalkingUsername;

    @Column(name = "sms_africas_talking_api_key_enc", columnDefinition = "TEXT")
    private String smsAfricasTalkingApiKeyEnc;
}
