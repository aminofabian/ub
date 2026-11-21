package zelisline.ub.globalcatalog.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.globalcatalog.api.dto.GlobalCatalogMedicineDtos.UpsertMedicineTemplateRequest;
import zelisline.ub.globalcatalog.domain.GlobalCatalog;
import zelisline.ub.globalcatalog.domain.GlobalProduct;
import zelisline.ub.globalcatalog.domain.GlobalProductMedicineDetails;
import zelisline.ub.globalcatalog.repository.GlobalCatalogRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductMedicineDetailsRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductRepository;
import zelisline.ub.pharmacy.domain.DispensingClass;
import zelisline.ub.pharmacy.repository.ItemMedicineDetailsRepository;

class GlobalCatalogMedicineServiceTest {

    private final GlobalProductRepository products = mock(GlobalProductRepository.class);
    private final GlobalCatalogRepository catalogs = mock(GlobalCatalogRepository.class);
    private final GlobalProductMedicineDetailsRepository medicines =
            mock(GlobalProductMedicineDetailsRepository.class);
    private final GlobalCatalogMedicineService service = new GlobalCatalogMedicineService(
            products, catalogs, medicines, mock(ItemMedicineDetailsRepository.class));

    private GlobalProduct product(String id, String kit, String barcode) {
        GlobalProduct p = new GlobalProduct();
        p.setId(id);
        p.setCatalogId("cat-1");
        p.setName("Product " + id);
        p.setStoreKitId(kit);
        p.setBarcode(barcode);
        when(products.findById(id)).thenReturn(Optional.of(p));
        return p;
    }

    private void catalogWith(GlobalProduct... all) {
        GlobalCatalog c = new GlobalCatalog();
        c.setId("cat-1");
        when(catalogs.findById("cat-1")).thenReturn(Optional.of(c));
        when(products.findByCatalogId("cat-1")).thenReturn(List.of(all));
    }

    private static byte[] csv(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void suggestedClassAcceptsCommonSpellingsButNeverUnclassified() {
        assertEquals(DispensingClass.PRESCRIPTION_ONLY,
                GlobalCatalogMedicineService.parseSuggestedClassOrThrow("prescription only"));
        assertEquals(DispensingClass.PRESCRIPTION_ONLY, GlobalCatalogMedicineService.parseSuggestedClassOrThrow("POM"));
        assertEquals(DispensingClass.OTC, GlobalCatalogMedicineService.parseSuggestedClassOrThrow("otc"));
        assertThrows(IllegalArgumentException.class,
                () -> GlobalCatalogMedicineService.parseSuggestedClassOrThrow("UNCLASSIFIED"));
        assertThrows(IllegalArgumentException.class,
                () -> GlobalCatalogMedicineService.parseSuggestedClassOrThrow("schedule 2"));
    }

    @Test
    void onlyPharmacyCatalogueProductsCanBeMarked() {
        product("gp-grocery", "grocery", null);
        assertThrows(ResponseStatusException.class, () -> service.upsert("gp-grocery",
                new UpsertMedicineTemplateRequest("Paracetamol", "500 mg", "Tablet", null, null, "OTC")));
        verify(medicines, never()).save(any());
    }

    @Test
    void aGroceryProductIsNeverAdoptedAsAMedicineEvenIfARowExists() {
        product("gp-grocery", null, null);
        when(medicines.findById("gp-grocery")).thenReturn(Optional.of(new GlobalProductMedicineDetails()));
        assertNull(service.templateFor("gp-grocery"));
    }

    @Test
    void adoptionTemplateCarriesTheSuggestion() {
        product("gp-1", "pharmacy", null);
        GlobalProductMedicineDetails d = new GlobalProductMedicineDetails();
        d.setGlobalProductId("gp-1");
        d.setGenericName("Amoxicillin");
        d.setSuggestedClass("PRESCRIPTION_ONLY");
        when(medicines.findById("gp-1")).thenReturn(Optional.of(d));
        var template = service.templateFor("gp-1");
        assertEquals("Amoxicillin", template.genericName());
        assertEquals(DispensingClass.PRESCRIPTION_ONLY, template.suggestedClass());
    }

    @Test
    void importMatchesByBarcodeAndReportsWhatItSkipped() {
        GlobalProduct amoxil = product("gp-1", "pharmacy", "6001");
        GlobalProduct bread = product("gp-2", "grocery", "7001");
        catalogWith(amoxil, bread);
        when(medicines.findById(any())).thenReturn(Optional.empty());
        String file = "barcode,generic_name,strength,dosage_form,suggested_class\n"
                + "6001,Amoxicillin,500 mg,Capsule,POM\n"
                + "7001,Flour,,,OTC\n"
                + "9999,Unknown,,,OTC\n"
                + "6001,,,,\n";

        var dry = service.importCsv("cat-1", csv(file), true);
        assertTrue(dry.dryRun());
        assertEquals(1, dry.created());
        verify(medicines, never()).save(any());

        var result = service.importCsv("cat-1", csv(file), false);
        assertEquals(4, result.rows());
        assertEquals(1, result.created());
        assertEquals(3, result.skipped().size());
        assertEquals(3, result.skipped().get(0).row());
        org.mockito.ArgumentCaptor<GlobalProductMedicineDetails> saved =
                org.mockito.ArgumentCaptor.forClass(GlobalProductMedicineDetails.class);
        verify(medicines).save(saved.capture());
        assertEquals("gp-1", saved.getValue().getGlobalProductId());
        assertEquals("PRESCRIPTION_ONLY", saved.getValue().getSuggestedClass());
    }

    @Test
    void aFileWithNoMatchingColumnIsRefused() {
        catalogWith();
        assertThrows(ResponseStatusException.class,
                () -> service.importCsv("cat-1", csv("name,generic_name\nAmoxil,Amoxicillin\n"), false));
    }
}
