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
        return new ResolvedSokoMindConfig(
                true, true, false, false,
                provider, "en-KE",
                "", "", "gpt-4o-mini", "gpt-4.1", "gpt-4o",
                "", "", "claude-haiku", "claude-sonnet",
                deepseekKey, "https://api.deepseek.com/chat/completions",
                "deepseek-v31.p.rapidapi.com", "DeepSeek-V3-0324",
                rapidapiKey,
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
}
