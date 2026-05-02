package zelisline.ub.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class CategoryCycleDetectorTest {

    @Test
    void detectsDirectSelfAsParentWhenEditing() {
        assertThat(CategoryCycleDetector.wouldIntroduceCycle("a", "a", id -> Optional.empty())).isTrue();
    }

    @Test
    void detectsWhenNewParentIsDescendantOfEditedNode() {
        // a -> b -> c; editing a to parent c introduces cycle (c chain reaches ... a if we attach a under c)
        // Walk from proposed parent c: c -> b -> a; hit a equals edited id -> cycle
        Map<String, String> parents = Map.of("b", "a", "c", "b");
        assertThat(CategoryCycleDetector.wouldIntroduceCycle("a", "c", id -> Optional.ofNullable(parents.get(id))))
                .isTrue();
    }

    @Test
    void allowsValidReparent() {
        Map<String, String> parents = Map.of("b", "a");
        assertThat(CategoryCycleDetector.wouldIntroduceCycle("c", "b", id -> Optional.ofNullable(parents.get(id))))
                .isFalse();
    }

    @Test
    void rootParentNeverCycles() {
        assertThat(CategoryCycleDetector.wouldIntroduceCycle("a", null, id -> Optional.empty())).isFalse();
    }
}
