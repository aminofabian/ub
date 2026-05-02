package zelisline.ub.identity.domain;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Slice 2 invariant: identity domain types are framework-agnostic
 * (PHASE_1_PLAN.md §2 — same rule as {@code tenancy.domain} in §1.6).
 */
@AnalyzeClasses(packages = "zelisline.ub.identity.domain")
class IdentityDomainArchTest {

    @ArchTest
    static final ArchRule domain_must_not_depend_on_spring = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..")
            .as("Identity domain types must not import org.springframework.*");
}
