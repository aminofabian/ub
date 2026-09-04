package zelisline.ub.credits.email.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class CustomerEmailMergeTest {

    @Test
    void replacesKnownTagsAndListsUnknown() {
        CustomerEmailMerge.Context ctx = new CustomerEmailMerge.Context(
                "Jane Wanjiku",
                "Jane",
                "jane@shop.co.ke",
                "0712345678",
                "Palmart",
                "https://palmart.kiosk.ke",
                new BigDecimal("100.50"),
                new BigDecimal("20.00"),
                42);
        CustomerEmailMerge.Result result = CustomerEmailMerge.apply(
                "Hello {{name}}",
                "<p>{{firstName}} at {{shop}} — {{mystery}}</p>",
                ctx);
        assertThat(result.subject()).isEqualTo("Hello Jane Wanjiku");
        assertThat(result.body()).contains("Jane at Palmart");
        assertThat(result.body()).contains("{{mystery}}");
        assertThat(result.unknownTags()).containsExactly("mystery");
    }

    @Test
    void findUnknownDoesNotRequireContext() {
        List<String> unknown = CustomerEmailMerge.findUnknown(
                "{{name}}", "<p>{{unknownTag}}</p>");
        assertThat(unknown).containsExactly("unknownTag");
    }
}
