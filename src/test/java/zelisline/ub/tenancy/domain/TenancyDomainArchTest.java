package zelisline.ub.tenancy.domain;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Slice 1 DoD: "{@code tenancy.domain} has zero Spring imports" — see
 * {@code docs/PHASE_1_PLAN.md} §1.6.
 *
 * <p>Domain types must remain framework-agnostic so they can be reused across
 * Phase 2+ modules without dragging Spring into the dependency closure.
 */
@AnalyzeClasses(packages = "zelisline.ub.tenancy.domain")
class TenancyDomainArchTest {

    @ArchTest
    static final ArchRule domain_must_not_depend_on_spring = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..")
            .as("Tenancy domain types must not import org.springframework.*");
}
