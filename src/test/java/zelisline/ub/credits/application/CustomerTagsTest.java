package zelisline.ub.credits.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class CustomerTagsTest {

    @Test
    void parseReadsJsonArrayAndToleratesGarbage() {
        assertThat(CustomerTags.parse("[\"wholesale\",\"family\"]"))
                .containsExactly("wholesale", "family");
        assertThat(CustomerTags.parse(null)).isEmpty();
        assertThat(CustomerTags.parse("")).isEmpty();
        assertThat(CustomerTags.parse("not json")).isEmpty();
        assertThat(CustomerTags.parse("[]")).isEmpty();
    }

    @Test
    void serializeNormalizesAndClearsToNull() {
        assertThat(CustomerTags.serialize(List.of(" Wholesale ", "wholesale", "", "family")))
                .isEqualTo("[\"Wholesale\",\"family\"]");
        assertThat(CustomerTags.serialize(List.of())).isNull();
        assertThat(CustomerTags.serialize(null)).isNull();
    }

    @Test
    void serializeCapsTagCountAndLength() {
        List<String> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            tooMany.add("tag-" + i);
        }
        assertThat(CustomerTags.normalize(tooMany)).hasSize(16);

        String longTag = "x".repeat(100);
        assertThat(CustomerTags.normalize(List.of(longTag, "ok"))).containsExactly("ok");
    }

    @Test
    void hasMatchesCaseInsensitively() {
        assertThat(CustomerTags.has("[\"Wholesale\"]", "wholesale")).isTrue();
        assertThat(CustomerTags.has("[\"wholesale2\"]", "wholesale")).isFalse();
        assertThat(CustomerTags.has(null, "wholesale")).isFalse();
        assertThat(CustomerTags.has("[\"wholesale\"]", null)).isFalse();
    }
}
