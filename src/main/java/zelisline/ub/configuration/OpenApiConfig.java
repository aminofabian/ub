package zelisline.ub.configuration;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

/**
 * OpenAPI 3.1 metadata for the Phase 1 contract snapshot
 * ({@code docs/openapi/phase-1.yaml}).
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI phase1OpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("UB — Phase 1 API")
                        .description("Identity, tenancy, and catalog backbone (Phase 1).")
                        .version("0.0.1-SNAPSHOT"))
                .servers(List.of(new Server().url("/").description("Current host")))
                .components(new Components()
                        .addSecuritySchemes("bearer", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Slice 3: access JWT via Authorization header."))
                        .addSecuritySchemes("apiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-API-Key")
                                .description(
                                        "Slice 3: tenant integration key (prefix + secret; returned once at create)."))
                        .addSecuritySchemes("cookieRefresh", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("ub_refresh")
                                .description(
                                        "Optional HttpOnly refresh cookie; native clients may use JSON body instead.")));
    }
}
