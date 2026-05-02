package zelisline.ub.identity.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Same refresh token used twice in parallel — one {@code 200}, one {@code 401},
 * rotated refresh is also dead (PHASE_1_PLAN.md §3.6).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class AuthRefreshConcurrencyIT {

    private static final String TENANT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String ROLE_ID = "22222222-2222-2222-2222-222222222201";

    @LocalServerPort
    private int port;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().build();

    @BeforeEach
    void seed() {
        userRepository.deleteAll();
        roleRepository.deleteAll();
        businessRepository.deleteAll();

        Business business = new Business();
        business.setId(TENANT);
        business.setName("Tenant A");
        business.setSlug("tenant-a-conc");
        businessRepository.save(business);

        Role owner = new Role();
        owner.setId(ROLE_ID);
        owner.setBusinessId(null);
        owner.setRoleKey("owner");
        owner.setName("Owner");
        owner.setSystem(true);
        roleRepository.save(owner);

        User user = new User();
        user.setBusinessId(TENANT);
        user.setEmail("owner@example.com");
        user.setName("Owner");
        user.setRoleId(ROLE_ID);
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash(passwordEncoder.encode("correct-password"));
        userRepository.save(user);
    }

    @Test
    void parallelRefreshWithSameToken_oneUnauthorizedAndNewRefreshInvalidated() throws Exception {
        String root = "http://127.0.0.1:" + port;

        HttpRequest loginReq = HttpRequest.newBuilder()
                .uri(URI.create(root + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", TENANT)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"owner@example.com\",\"password\":\"correct-password\"}",
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> loginResp = httpClient.send(loginReq, HttpResponse.BodyHandlers.ofString());
        assertThat(loginResp.statusCode()).isEqualTo(200);
        JsonNode loginJson = objectMapper.readTree(loginResp.body());
        String refresh = loginJson.get("refreshToken").asText();

        String refreshBody = objectMapper.writeValueAsString(java.util.Map.of("refreshToken", refresh));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<HttpResponse<String>> f1 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(root + "/api/v1/auth/refresh"))
                        .header("Content-Type", "application/json")
                        .header("X-Tenant-Id", TENANT)
                        .POST(HttpRequest.BodyPublishers.ofString(refreshBody, StandardCharsets.UTF_8))
                        .build();
                return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            });
            Future<HttpResponse<String>> f2 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(root + "/api/v1/auth/refresh"))
                        .header("Content-Type", "application/json")
                        .header("X-Tenant-Id", TENANT)
                        .POST(HttpRequest.BodyPublishers.ofString(refreshBody, StandardCharsets.UTF_8))
                        .build();
                return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            });

            HttpResponse<String> r1 = f1.get(30, TimeUnit.SECONDS);
            HttpResponse<String> r2 = f2.get(30, TimeUnit.SECONDS);
            int c200 = 0;
            int c401 = 0;
            for (HttpResponse<String> r : new HttpResponse[] {r1, r2}) {
                int c = r.statusCode();
                if (c == 200) {
                    c200++;
                } else if (c == 401) {
                    c401++;
                }
            }
            assertThat(c200).isEqualTo(1);
            assertThat(c401).isEqualTo(1);

            String newRefresh = null;
            if (r1.statusCode() == 200) {
                newRefresh = objectMapper.readTree(r1.body()).get("refreshToken").asText();
            } else if (r2.statusCode() == 200) {
                newRefresh = objectMapper.readTree(r2.body()).get("refreshToken").asText();
            }
            assertThat(newRefresh).isNotNull();

            HttpRequest replayReq = HttpRequest.newBuilder()
                    .uri(URI.create(root + "/api/v1/auth/refresh"))
                    .header("Content-Type", "application/json")
                    .header("X-Tenant-Id", TENANT)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(java.util.Map.of("refreshToken", newRefresh)),
                            StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> replayNew = httpClient.send(replayReq, HttpResponse.BodyHandlers.ofString());
            assertThat(replayNew.statusCode()).isEqualTo(401);
        } finally {
            pool.shutdownNow();
        }
    }
}
