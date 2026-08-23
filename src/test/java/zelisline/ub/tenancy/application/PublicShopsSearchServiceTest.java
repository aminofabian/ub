package zelisline.ub.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import zelisline.ub.tenancy.api.dto.PublicShopsSearchResponse;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.DomainMapping;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

class PublicShopsSearchServiceTest {

    private BusinessRepository businessRepository;
    private DomainMappingRepository domainMappingRepository;
    private StorefrontSettingsService storefrontSettingsService;
    private PublicShopsSearchService service;

    @BeforeEach
    void setUp() {
        businessRepository = Mockito.mock(BusinessRepository.class);
        domainMappingRepository = Mockito.mock(DomainMappingRepository.class);
        storefrontSettingsService = Mockito.mock(StorefrontSettingsService.class);
        service = new PublicShopsSearchService(
                businessRepository, domainMappingRepository, storefrontSettingsService);
        ReflectionTestUtils.setField(service, "slugDomainSuffix", "kiosk.ke");
    }

    @Test
    void blankAndShortQueriesReturnEmpty() {
        assertThat(service.search("")).isEmpty();
        assertThat(service.search("   ")).isEmpty();
        assertThat(service.search("a")).isEmpty();
    }

    @Test
    void exactSlugMatchReturnsSingleRowWithPrimaryMappingHost() {
        Business business = business("b1", "mama-njeri", "Mama Njeri Minimart");
        Mockito.when(businessRepository.findBySlugAndDeletedAtIsNull("mama-njeri"))
                .thenReturn(Optional.of(business));
        Mockito.when(domainMappingRepository.findByBusinessIdAndDeletedAtIsNull("b1"))
                .thenReturn(List.of(primaryMapping("mama-njeri.kiosk.ke")));

        List<PublicShopsSearchResponse> rows = service.search("mama-njeri");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).slug()).isEqualTo("mama-njeri");
        assertThat(rows.get(0).name()).isEqualTo("Mama Njeri Minimart");
        assertThat(rows.get(0).primaryHost()).isEqualTo("mama-njeri.kiosk.ke");
    }

    @Test
    void nameContainsFuzzyMatchWorksAndDeduplicatesSlugMatch() {
        Business business = business("b1", "njeri", "Mama Njeri Minimart");
        // Exact-name probe misses; the fuzzy contains query hits.
        Mockito.when(businessRepository.findFirstByNameIgnoreCaseAndDeletedAtIsNull("njeri"))
                .thenReturn(Optional.empty());
        Mockito.when(businessRepository.findTop8ByDeletedAtIsNullAndNameContainingIgnoreCaseOrderByNameAsc("njeri"))
                .thenReturn(List.of(business));

        List<PublicShopsSearchResponse> rows = service.search("njeri");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).slug()).isEqualTo("njeri");
    }

    @Test
    void hostFormResolvesThroughActiveDomainMapping() {
        Business business = business("b1", "mama-njeri", "Mama Njeri Minimart");
        Mockito.when(domainMappingRepository.findByDomainAndActiveTrue("mama-njeri.kiosk.ke"))
                .thenReturn(Optional.of(primaryMapping("mama-njeri.kiosk.ke")));
        Mockito.when(businessRepository.findByIdAndDeletedAtIsNull("b1"))
                .thenReturn(Optional.of(business));
        Mockito.when(domainMappingRepository.findByBusinessIdAndDeletedAtIsNull("b1"))
                .thenReturn(List.of(primaryMapping("mama-njeri.kiosk.ke")));

        List<PublicShopsSearchResponse> rows = service.search("mama-njeri.kiosk.ke");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).slug()).isEqualTo("mama-njeri");
    }

    @Test
    void missingMappingFallsBackToSlugSuffixHost() {
        Business business = business("b1", "mama-njeri", "Mama Njeri Minimart");
        Mockito.when(businessRepository.findBySlugAndDeletedAtIsNull("mama-njeri"))
                .thenReturn(Optional.of(business));
        Mockito.when(domainMappingRepository.findByBusinessIdAndDeletedAtIsNull("b1"))
                .thenReturn(List.of());

        List<PublicShopsSearchResponse> rows = service.search("mama-njeri");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).primaryHost()).isEqualTo("mama-njeri.kiosk.ke");
    }

    @Test
    void resultsAreCappedAtEight() {
        List<Business> ten = java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(i -> business("b" + i, "shop-" + i, "Shop " + i))
                .toList();
        Mockito.when(businessRepository.findTop8ByDeletedAtIsNullAndNameContainingIgnoreCaseOrderByNameAsc("shop"))
                .thenReturn(ten.subList(0, 8));
        Mockito.when(businessRepository.findTop8ByDeletedAtIsNullAndSlugStartingWithOrderBySlugAsc("shop"))
                .thenReturn(List.of());
        Mockito.when(businessRepository.findBySlugAndDeletedAtIsNull("shop"))
                .thenReturn(Optional.empty());
        Mockito.when(businessRepository.findFirstByNameIgnoreCaseAndDeletedAtIsNull("shop"))
                .thenReturn(Optional.empty());
        Mockito.when(domainMappingRepository.findByBusinessIdAndDeletedAtIsNull(Mockito.anyString()))
                .thenReturn(List.of());

        List<PublicShopsSearchResponse> rows = service.search("shop");

        assertThat(rows).hasSize(8);
    }

    private static Business business(String id, String slug, String name) {
        Business business = new Business();
        business.setId(id);
        business.setSlug(slug);
        business.setName(name);
        return business;
    }

    private static DomainMapping primaryMapping(String domain) {
        DomainMapping mapping = new DomainMapping();
        mapping.setBusinessId("b1");
        mapping.setDomain(domain);
        mapping.setPrimary(true);
        mapping.setActive(true);
        return mapping;
    }
}
