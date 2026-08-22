package zelisline.ub.platform.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "platform_sokomind_settings")
@Getter
@Setter
public class PlatformSokoMindSettings {

    public static final String SINGLETON_ID = "00000000-0000-0000-0000-000000000001";

    public static final String PROVIDER_OPENAI = "openai";
    public static final String PROVIDER_ANTHROPIC = "anthropic";
    public static final String PROVIDER_DEEPSEEK = "deepseek";
    public static final String PROVIDER_RAPIDAPI_DEEPSEEK = "rapidapi_deepseek";

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "sokomind_enabled", nullable = false)
    private boolean sokomindEnabled = false;

    @Column(name = "guide_enabled", nullable = false)
    private boolean guideEnabled = true;

    @Column(name = "brain_enabled", nullable = false)
    private boolean brainEnabled = false;

    @Column(name = "eye_enabled", nullable = false)
    private boolean eyeEnabled = false;

    @Column(name = "primary_provider", length = 32, nullable = false)
    private String primaryProvider = PROVIDER_OPENAI;

    @Column(name = "default_locale", length = 16, nullable = false)
    private String defaultLocale = "en-KE";

    @Column(name = "openai_api_key_enc", columnDefinition = "TEXT")
    private String openaiApiKeyEnc;

    @Column(name = "openai_base_url", length = 512)
    private String openaiBaseUrl;

    @Column(name = "openai_mini_model", length = 128)
    private String openaiMiniModel;

    @Column(name = "openai_smart_model", length = 128)
    private String openaiSmartModel;

    @Column(name = "openai_vision_model", length = 128)
    private String openaiVisionModel;

    @Column(name = "anthropic_api_key_enc", columnDefinition = "TEXT")
    private String anthropicApiKeyEnc;

    @Column(name = "anthropic_base_url", length = 512)
    private String anthropicBaseUrl;

    @Column(name = "anthropic_mini_model", length = 128)
    private String anthropicMiniModel;

    @Column(name = "anthropic_smart_model", length = 128)
    private String anthropicSmartModel;

    @Column(name = "deepseek_api_key_enc", columnDefinition = "TEXT")
    private String deepseekApiKeyEnc;

    @Column(name = "rapidapi_deepseek_api_key_enc", columnDefinition = "TEXT")
    private String rapidapiDeepseekApiKeyEnc;

    @Column(name = "deepseek_base_url", length = 512)
    private String deepseekBaseUrl;

    @Column(name = "deepseek_host", length = 255)
    private String deepseekHost;

    @Column(name = "deepseek_model", length = 128)
    private String deepseekModel;

    @Column(name = "industry_compare_enabled", nullable = false)
    private boolean industryCompareEnabled = false;

    @Column(name = "industry_compare_min_twins", nullable = false)
    private int industryCompareMinTwins = 8;

    @Column(name = "daily_token_budget_per_tenant")
    private Integer dailyTokenBudgetPerTenant;

    @Column(name = "max_tool_calls_per_request", nullable = false)
    private int maxToolCallsPerRequest = 8;

    @Column(name = "system_prompt_extra", columnDefinition = "TEXT")
    private String systemPromptExtra;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
