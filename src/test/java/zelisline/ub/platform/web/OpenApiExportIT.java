package zelisline.ub.platform.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Regenerates {@code docs/openapi/phase-1.yaml} from the live springdoc model.
 *
 * <p>Run: {@code ./gradlew test --tests 'zelisline.ub.platform.web.OpenApiExportIT' -DexportOpenapi=true}
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "exportOpenapi", matches = "true")
class OpenApiExportIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void writePhase1Yaml() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/openapi").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> tree = mapper.readValue(json, new TypeReference<>() {});

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setPrettyFlow(true);
        Yaml yaml = new Yaml(options);

        Path out = Path.of(System.getProperty("user.dir"), "docs/openapi/phase-1.yaml");
        Files.createDirectories(out.getParent());

        String header = """
                # UB Phase 1 — OpenAPI 3.1 contract snapshot
                # Generated: OpenApiExportIT (./gradlew test --tests zelisline.ub.platform.web.OpenApiExportIT -DexportOpenapi=true)
                #
                """;
        Files.writeString(out, header + yaml.dump(tree), StandardCharsets.UTF_8);
    }
}
