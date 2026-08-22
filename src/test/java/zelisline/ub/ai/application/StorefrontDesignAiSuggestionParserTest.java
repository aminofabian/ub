package zelisline.ub.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import zelisline.ub.ai.api.dto.StorefrontDesignSuggestResponse;

class StorefrontDesignAiSuggestionParserTest {

    @Test
    void parsesFullSuggestion() {
        String content = """
                {
                  "summary": "Warmed it up with soft corners and a friendlier offer.",
                  "brandKit": { "radius": "soft", "buttons": "pill", "density": "airy", "surface": "#FFFCF5" },
                  "copy": {
                    "tagline": "Pens, paper and everyday essentials.",
                    "description": "A neighbourhood shop in the city centre.",
                    "announcement": "Free delivery today",
                    "promoTitle": "20% OFF this week",
                    "coupon": "WELCOME10",
                    "heroHeadline": "Fresh supplies, delivered",
                    "contactHeading": "Come visit"
                  }
                }
                """;
        StorefrontDesignSuggestResponse out = StorefrontDesignAiService.parseSuggestions(content, "req-1");
        assertThat(out.requestId()).isEqualTo("req-1");
        assertThat(out.summary()).contains("soft corners");
        assertThat(out.brandKit()).isNotNull();
        assertThat(out.brandKit().radius()).isEqualTo("soft");
        assertThat(out.brandKit().buttons()).isEqualTo("pill");
        assertThat(out.brandKit().density()).isEqualTo("airy");
        assertThat(out.brandKit().surface()).isEqualTo("#fffcf5");
        assertThat(out.copy()).isNotNull();
        assertThat(out.copy().tagline()).isEqualTo("Pens, paper and everyday essentials.");
        assertThat(out.copy().promoTitle()).isEqualTo("20% OFF this week");
        assertThat(out.copy().heroHeadline()).isEqualTo("Fresh supplies, delivered");
        assertThat(out.copy().contactHeading()).isEqualTo("Come visit");
        assertThat(out.copy().socialHeading()).isNull();
    }

    @Test
    void stripsMarkdownFences() {
        String content = "```json\n{ \"summary\": \"done\", \"brandKit\": { \"radius\": \"round\" } }\n```";
        StorefrontDesignSuggestResponse out = StorefrontDesignAiService.parseSuggestions(content, "req-2");
        assertThat(out.summary()).isEqualTo("done");
        assertThat(out.brandKit().radius()).isEqualTo("round");
    }

    @Test
    void extractsJsonFromProseAroundIt() {
        String content = "Here you go:\n{ \"summary\": \"done\" }\nHope that helps!";
        StorefrontDesignSuggestResponse out = StorefrontDesignAiService.parseSuggestions(content, "req-3");
        assertThat(out.summary()).isEqualTo("done");
    }

    @Test
    void dropsInvalidEnumsAndHex() {
        String content = """
                {
                  "summary": "s",
                  "brandKit": {
                    "radius": "squiggly",
                    "buttons": "outline",
                    "density": "MEGA",
                    "surface": "not-a-color"
                  }
                }
                """;
        StorefrontDesignSuggestResponse out = StorefrontDesignAiService.parseSuggestions(content, "req-4");
        assertThat(out.brandKit()).isNotNull();
        assertThat(out.brandKit().radius()).isNull();
        assertThat(out.brandKit().buttons()).isEqualTo("outline");
        assertThat(out.brandKit().density()).isNull();
        assertThat(out.brandKit().surface()).isNull();
    }

    @Test
    void omitsEmptyGroupsAndRejectsGarbage() {
        StorefrontDesignSuggestResponse empty = StorefrontDesignAiService.parseSuggestions(
                "{\"summary\":\"only a summary\"}", "req-5");
        assertThat(empty.brandKit()).isNull();
        assertThat(empty.copy()).isNull();

        StorefrontDesignSuggestResponse garbage = StorefrontDesignAiService.parseSuggestions(
                "this is not json at all", "req-6");
        assertThat(garbage.summary()).isNull();
        assertThat(garbage.brandKit()).isNull();

        StorefrontDesignSuggestResponse blank = StorefrontDesignAiService.parseSuggestions(null, "req-7");
        assertThat(blank.summary()).isNull();
    }

    @Test
    void truncatesOverlongCopy() {
        String longTagline = "x".repeat(300);
        String content = "{\"copy\":{\"tagline\":\"" + longTagline + "\"}}";
        StorefrontDesignSuggestResponse out = StorefrontDesignAiService.parseSuggestions(content, "req-8");
        assertThat(out.copy().tagline()).hasSize(120);
    }
}
