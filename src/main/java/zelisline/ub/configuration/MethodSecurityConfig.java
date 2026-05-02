package zelisline.ub.configuration;

import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;

/**
 * Wires the custom {@link PermissionEvaluator} into {@code @PreAuthorize}
 * SpEL (PHASE_1_PLAN.md §2.5 DoD).
 *
 * <p>{@code @EnableMethodSecurity} is declared on {@link SecurityConfig}; this
 * class only contributes the expression-handler {@link Bean}.
 */
@Configuration
public class MethodSecurityConfig {

    @Bean
    @Primary
    static MethodSecurityExpressionHandler methodSecurityExpressionHandler(
            ApplicationContext applicationContext,
            PermissionEvaluator permissionEvaluator
    ) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setApplicationContext(applicationContext);
        handler.setPermissionEvaluator(permissionEvaluator);
        return handler;
    }
}
