package zelisline.ub.platform.loadtest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import com.sun.net.httpserver.HttpServer;

import zelisline.ub.identity.domain.SuperAdmin;
import zelisline.ub.identity.repository.SuperAdminRepository;

/**
 * Super Admin → Platform → Load test API: security, capacity readout, and the
 * full run lifecycle (start → reject concurrent run → poll → history).
 *
 * <p>The load generator is pointed at a tiny stub HTTP server on a fixed port
 * (not this instance) so the staircase exercises the real virtual-thread
 * request loop end to end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.load-test.base-url=http://127.0.0.1:18987",
        "app.actuator.prometheus-token=metrics-secret-token"
})
class LoadTestApiIT {

    private static final int STUB_PORT = 18987;

    private static HttpServer stubServer;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SuperAdminRepository superAdminRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String saToken;

    @BeforeAll
    static void startStub() throws Exception {
        stubServer = HttpServer.create(new InetSocketAddress(STUB_PORT), 0);
        stubServer.createContext("/", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        stubServer.start();
    }

    @AfterAll
    static void stopStub() {
        if (stubServer != null) {
            stubServer.stop(0);
        }
    }

    @BeforeEach
    void seedAdmin() {
        superAdminRepository.deleteAll();
        SuperAdmin admin = new SuperAdmin();
        admin.setEmail("ops-loadtest@example.com");
        admin.setName("Ops Load Test");
        admin.setPasswordHash(passwordEncoder.encode("super-secret-pass"));
        admin.setActive(true);
        superAdminRepository.save(admin);
    }

    @Test
    void statusRequiresSuperAdmin() throws Exception {
        // Unauthenticated calls are rejected before the controller runs. The
        // stateless security chain answers 403 for anonymous access (no entry
        // point is configured); invalid Bearer tokens get 401 from the JWT filter.
        mockMvc.perform(get("/api/v1/super-admin/load-test/status"))
                .andExpect(status().isForbidden());
    }

    @Test
    void statusExposesCapacityShape() throws Exception {
        login();

        mockMvc.perform(get("/api/v1/super-admin/load-test/status")
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(false))
                .andExpect(jsonPath("$.run").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.capacity.selfTestBaseUrl").value("http://127.0.0.1:18987"))
                .andExpect(jsonPath("$.capacity.dbPoolMax").isNumber())
                .andExpect(jsonPath("$.capacity.activeWsConnections").isNumber())
                .andExpect(jsonPath("$.history").isArray());
    }

    @Test
    void runLifecycleProducesHistoryAndRejectsConcurrentRun() throws Exception {
        login();

        MvcResult start = mockMvc.perform(post("/api/v1/super-admin/load-test/run")
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"path":"/health","maxConcurrency":4,"steps":2,"secondsPerStep":1,"targetP95Ms":800}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").isNotEmpty())
                .andReturn();
        String runId = JsonPath.read(start.getResponse().getContentAsString(), "$.runId");

        // A second run while one is active is rejected with 409.
        mockMvc.perform(post("/api/v1/super-admin/load-test/run")
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/health\",\"maxConcurrency\":2,\"steps\":1,\"secondsPerStep\":1}"))
                .andExpect(status().isConflict());

        awaitRunCompletion(runId);

        mockMvc.perform(get("/api/v1/super-admin/load-test/status")
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(false))
                .andExpect(jsonPath("$.history[0].runId").value(runId))
                .andExpect(jsonPath("$.history[0].path").value("/health"))
                .andExpect(jsonPath("$.history[0].steps.length()").value(2))
                .andExpect(jsonPath("$.history[0].steps[0].concurrency").value(2))
                .andExpect(jsonPath("$.history[0].steps[1].concurrency").value(4))
                .andExpect(jsonPath("$.history[0].steps[1].requests", Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.history[0].steps[1].errors").value(0))
                .andExpect(jsonPath("$.history[0].recommendedConcurrentUsers").value(4))
                .andExpect(jsonPath("$.history[0].peakRps", Matchers.greaterThan(0.0)));
    }

    @Test
    void cancelStopsCurrentRun() throws Exception {
        login();

        // Clear any run left behind by an earlier test (cancel is idempotent).
        mockMvc.perform(post("/api/v1/super-admin/load-test/cancel")
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/super-admin/load-test/run")
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"path":"/health","maxConcurrency":4,"steps":4,"secondsPerStep":10}
                                """))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/super-admin/load-test/cancel")
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isAccepted());

        // Cancel is acknowledged immediately; the run finishes within seconds.
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String json = mockMvc.perform(get("/api/v1/super-admin/load-test/status")
                            .header("Authorization", "Bearer " + saToken))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            if (Boolean.FALSE.equals(JsonPath.read(json, "$.running"))) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Run did not stop after cancel within 10s");
    }

    @Test
    void invalidPathIsRejected() throws Exception {
        login();

        mockMvc.perform(post("/api/v1/super-admin/load-test/run")
                        .header("Authorization", "Bearer " + saToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"not-a-path\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void prometheusMetricsRequireSuperAdminOrBearerSecret() throws Exception {
        login();

        // Anonymous scrape is rejected.
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().is4xxClientError());

        // Super-admin JWT works.
        mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(Matchers.containsString("jvm_memory_used_bytes")));

        // Wrong bearer is rejected (401/403 depending on the filter that catches it).
        mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", "Bearer wrong-token"))
                .andExpect(status().is4xxClientError());

        // The configured long-lived scrape secret works (Grafana use case).
        mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", "Bearer metrics-secret-token"))
                .andExpect(status().isOk());
    }

    @Test
    void loadTestRequestsAreTaggedInRequestLog() throws Exception {
        login();
        String runId = "lt-1750000000000-42";

        mockMvc.perform(get("/api/v1/super-admin/me")
                        .header("Authorization", "Bearer " + saToken)
                        .header("X-Palmart-Load-Test", runId))
                .andExpect(status().isOk());

        // The request-log feed exposes the marker and can filter to it.
        mockMvc.perform(get("/api/v1/super-admin/platform/request-logs")
                        .param("loadTestRunId", "*")
                        .param("limit", "10")
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].loadTestRunId").value(runId))
                .andExpect(jsonPath("$[0].path").value("/api/v1/super-admin/me"));
    }

    private void login() throws Exception {
        String json = mockMvc.perform(post("/api/v1/super-admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ops-loadtest@example.com","password":"super-secret-pass"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        saToken = JsonPath.read(json, "$.accessToken");
    }

    private void awaitRunCompletion(String runId) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            String json = mockMvc.perform(get("/api/v1/super-admin/load-test/status")
                            .header("Authorization", "Bearer " + saToken))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            Integer historyLength = JsonPath.read(json, "$.history.length()");
            if (historyLength != null && historyLength > 0) {
                String firstRunId = JsonPath.read(json, "$.history[0].runId");
                if (runId.equals(firstRunId)) {
                    return;
                }
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Run " + runId + " did not appear in history within 20s");
    }
}
