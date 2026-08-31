package zelisline.ub.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KenyanShelfHintsTest {

    @Test
    void blueBandIsDairyMargarineNotCookingFat() {
        var hint = KenyanShelfHints.match("Blue Band Original 500g", null);
        assertThat(hint).isNotNull();
        assertThat(hint.preferredDepartment()).isEqualTo("Dairy");
        assertThat(hint.preferredCategory()).isEqualTo("Margarine");
        assertThat(hint.avoids("Grocery")).isTrue();
        assertThat(hint.avoids("Cooking fat")).isTrue();
    }

    @Test
    void kimboIsCookingFat() {
        var hint = KenyanShelfHints.match("Kimbo 1kg", "Kimbo");
        assertThat(hint).isNotNull();
        assertThat(hint.preferredCategory()).isEqualTo("Cooking fat");
    }

    @Test
    void unknownBrandHasNoHint() {
        assertThat(KenyanShelfHints.match("Random soap", null)).isNull();
    }
}
