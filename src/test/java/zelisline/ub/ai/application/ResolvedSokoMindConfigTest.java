package zelisline.ub.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The two DeepSeek modes are separate: a direct key does not satisfy the
 * RapidAPI mode and vice versa (this conflation caused "right key, still 401").
 */
class ResolvedSokoMindConfigTest {

    private static ResolvedSokoMindConfig config(
            String provider,
            String deepseekKey,
            String rapidapiKey
    ) {
        return config(provider, "", deepseekKey, rapidapiKey, "");
    }

    private static ResolvedSokoMindConfig config(
            String provider,
            String openaiKey,
            String deepseekKey,
            String rapidapiKey,
            String openrouterKey
    ) {
        return new ResolvedSokoMindConfig(
                true, true, false, false,
                provider, "en-KE",
                openaiKey, "", "gpt-4o-mini", "gpt-4.1", "gpt-4o",
                "", "", "claude-haiku", "claude-sonnet",
                deepseekKey, "https://api.deepseek.com/chat/completions",
                "deepseek-v31.p.rapidapi.com", "DeepSeek-V3-0324",
                rapidapiKey,
                openrouterKey, "https://openrouter.ai/api/v1",
                "z-ai/glm-5.3-flash", "z-ai/glm-4.6", "google/gemini-2.5-flash-image",
                false, 8, null, 8, null);
    }

    @Test
    void directDeepseekNeedsTheDirectKey() {
        assertThat(config("deepseek", "sk-direct", "").primaryProviderConfigured()).isTrue();
        assertThat(config("deepseek", "", "rapidapi-key").primaryProviderConfigured()).isFalse();
    }

    @Test
    void rapidapiDeepseekNeedsTheRapidapiKey() {
        assertThat(config("rapidapi_deepseek", "", "rapidapi-key").primaryProviderConfigured()).isTrue();
        assertThat(config("rapidapi_deepseek", "sk-direct", "").primaryProviderConfigured()).isFalse();
    }

    @Test
    void otherProvidersAreUnaffected() {
        assertThat(config("openai", "", "").primaryProviderConfigured()).isFalse();
        assertThat(config("unknown", "sk-direct", "").primaryProviderConfigured()).isFalse();
    }

    @Test
    void openrouterNeedsTheOpenrouterKey() {
        assertThat(config("openrouter", "", "", "", "sk-or-v1").primaryProviderConfigured()).isTrue();
        assertThat(config("openrouter", "sk-openai", "sk-direct", "", "").primaryProviderConfigured()).isFalse();
    }

    @Test
    void imageGenerationNeedsEnabledOpenaiOrOpenrouterKey() {
        ResolvedSokoMindConfig withOpenai = config("deepseek", "sk-openai", "sk-direct", "", "");
        assertThat(withOpenai.imageGenerationAvailable()).isTrue();
        assertThat(withOpenai.imageProvider()).isEqualTo("openai");

        ResolvedSokoMindConfig withOpenrouter = config("openrouter", "", "", "", "sk-or-v1");
        assertThat(withOpenrouter.imageGenerationAvailable()).isTrue();
        assertThat(withOpenrouter.imageProvider()).isEqualTo("openrouter");

        assertThat(config("openai", "", "").imageGenerationAvailable()).isFalse();
        assertThat(config("openai", "", "").imageProvider()).isEmpty();
    }

    @Test
    void openrouterPrimaryPrefersOpenrouterImagesEvenIfOpenaiIsKeyed() {
        ResolvedSokoMindConfig both = config("openrouter", "sk-openai", "", "", "sk-or-v1");
        assertThat(both.imageProvider()).isEqualTo("openrouter");
        assertThat(config("openai", "sk-openai", "", "", "sk-or-v1").imageProvider()).isEqualTo("openai");
    }
}
