package zelisline.ub.globalcatalog.application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.globalcatalog.api.dto.GlobalCatalogMedicineDtos.MedicineImportResponse;
import zelisline.ub.globalcatalog.api.dto.GlobalCatalogMedicineDtos.MedicineImportSkip;
import zelisline.ub.globalcatalog.api.dto.GlobalCatalogMedicineDtos.MedicineTemplateResponse;
import zelisline.ub.globalcatalog.api.dto.GlobalCatalogMedicineDtos.UpsertMedicineTemplateRequest;
import zelisline.ub.globalcatalog.domain.GlobalCatalog;
import zelisline.ub.globalcatalog.domain.GlobalProduct;
import zelisline.ub.globalcatalog.domain.GlobalProductMedicineDetails;
import zelisline.ub.globalcatalog.domain.GlobalProductStatus;
import zelisline.ub.globalcatalog.repository.GlobalCatalogRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductMedicineDetailsRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductRepository;
import zelisline.ub.pharmacy.application.CatalogueMedicineTemplate;
import zelisline.ub.pharmacy.domain.DispensingClass;
import zelisline.ub.pharmacy.domain.ItemMedicineDetails;
import zelisline.ub.pharmacy.repository.ItemMedicineDetailsRepository;

/**
 * Medicine details on platform catalogue products (ADR 0033).
 *
 * <p>Only products in the pharmacy vertical can be marked, because only pharmacy shops can browse
 * that vertical and only they have the screens to confirm a class. The suggested class is advice
 * from whoever curates the catalogue: it is copied to a shop as a suggestion and the shop's
 * pharmacist decides.</p>
 */
@Service
@RequiredArgsConstructor
public class GlobalCatalogMedicineService {

    /** Store-kit id of the vertical whose products may carry medicine details. */
    public static final String PHARMACY_KIT = "pharmacy";

    public static final int MAX_IMPORT_ROWS = 5_000;

    private static final String DEFAULT_CATALOG_CODE = "default";

    private static final String[] CSV_HEADERS = {
            "id", "name", "brand", "size", "barcode", "sku_template",
            "generic_name", "strength", "dosage_form", "pack_size", "manufacturer", "suggested_class"
    };

    private final GlobalProductRepository globalProductRepository;
    private final GlobalCatalogRepository globalCatalogRepository;
    private final GlobalProductMedicineDetailsRepository medicineRepository;
    private final ItemMedicineDetailsRepository itemMedicineDetailsRepository;

    @Transactional(readOnly = true)
    public MedicineTemplateResponse get(String globalProductId) {
        GlobalProduct product = requireProduct(globalProductId);
        return medicineRepository.findById(product.getId())
                .map(GlobalCatalogMedicineService::toResponse)
                .orElseGet(() -> notMarked(product.getId()));
    }

    @Transactional
    public MedicineTemplateResponse upsert(String globalProductId, UpsertMedicineTemplateRequest req) {
        GlobalProduct product = requireProduct(globalProductId);
        requirePharmacyProduct(product);
        String suggested = parseSuggestedClass(req.suggestedClass());
        GlobalProductMedicineDetails d = medicineRepository.findById(product.getId()).orElse(null);
        if (d == null) {
            d = new GlobalProductMedicineDetails();
            d.setGlobalProductId(product.getId());
        }
        d.setGenericName(blankToNull(req.genericName()));
        d.setStrength(blankToNull(req.strength()));
        d.setDosageForm(blankToNull(req.dosageForm()));
        d.setPackSize(blankToNull(req.packSize()));
        d.setManufacturer(blankToNull(req.manufacturer()));
        d.setSuggestedClass(suggested);
        return toResponse(medicineRepository.save(d));
    }

    /** Stops the product being adopted as a medicine. Shops that already adopted it keep theirs. */
    @Transactional
    public void remove(String globalProductId) {
        GlobalProduct product = requireProduct(globalProductId);
        medicineRepository.findById(product.getId()).ifPresent(medicineRepository::delete);
    }

    /**
     * What a shop's medicine record should start from when it adopts this product, or null when
     * the product is not a pharmacy-vertical medicine.
     */
    @Transactional(readOnly = true)
    public CatalogueMedicineTemplate templateFor(String globalProductId) {
        if (globalProductId == null || globalProductId.isBlank()) {
            return null;
        }
        GlobalProduct product = globalProductRepository.findById(globalProductId).orElse(null);
        if (product == null || !isPharmacyProduct(product)) {
            return null;
        }
        GlobalProductMedicineDetails d = medicineRepository.findById(product.getId()).orElse(null);
        if (d == null) {
            return null;
        }
        DispensingClass suggested = null;
        if (d.getSuggestedClass() != null) {
            try {
                suggested = parseSuggestedClassOrThrow(d.getSuggestedClass());
            } catch (IllegalArgumentException ignored) {
                suggested = null;
            }
        }
        return new CatalogueMedicineTemplate(
                d.getGenericName(), d.getStrength(), d.getDosageForm(), d.getPackSize(), d.getManufacturer(),
                suggested);
    }

    /**
     * Promote: copies a source shop's medicine record onto the catalogue product it was promoted
     * to. The shop's confirmed class becomes the catalogue's suggestion. Does nothing when the
     * source item is not a medicine. The details only take effect on adoption once the product is
     * in the pharmacy vertical. Runs in its own transaction so a failure cannot undo the promote.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void copyFromTenantItem(String globalProductId, String businessId, String itemId) {
        if (globalProductId == null || businessId == null || itemId == null) {
            return;
        }
        ItemMedicineDetails source = itemMedicineDetailsRepository
                .findByItemIdAndBusinessId(itemId, businessId)
                .orElse(null);
        GlobalProduct product = globalProductRepository.findById(globalProductId).orElse(null);
        if (source == null || product == null) {
            return;
        }
        GlobalProductMedicineDetails d = medicineRepository.findById(globalProductId).orElse(null);
        if (d == null) {
            d = new GlobalProductMedicineDetails();
            d.setGlobalProductId(globalProductId);
        }
        d.setGenericName(source.getGenericName());
        d.setStrength(source.getStrength());
        d.setDosageForm(source.getDosageForm());
        d.setPackSize(source.getPackSize());
        d.setManufacturer(source.getManufacturer());
        if (source.getDispensingClass() != null && source.getDispensingClass() != DispensingClass.UNCLASSIFIED) {
            d.setSuggestedClass(source.getDispensingClass().name());
        }
        medicineRepository.save(d);
    }

    /** Every live pharmacy-vertical product with its medicine columns, ready to fill in and re-upload. */
    @Transactional(readOnly = true)
    public byte[] exportCsv(String catalogId) {
        GlobalCatalog catalog = requireCatalog(catalogId);
        List<GlobalProduct> products = globalProductRepository.findByCatalogId(catalog.getId()).stream()
                .filter(p -> !GlobalProductStatus.ARCHIVED.equals(p.getStatus()))
                .filter(GlobalCatalogMedicineService::isPharmacyProduct)
                .sorted((a, b) -> String.valueOf(a.getName()).compareToIgnoreCase(String.valueOf(b.getName())))
                .toList();
        Map<String, GlobalProductMedicineDetails> byId = new HashMap<>();
        if (!products.isEmpty()) {
            for (GlobalProductMedicineDetails d : medicineRepository.findByGlobalProductIdIn(
                    products.stream().map(GlobalProduct::getId).toList())) {
                byId.put(d.getGlobalProductId(), d);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
                CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setHeader(CSV_HEADERS).build())) {
            for (GlobalProduct p : products) {
                GlobalProductMedicineDetails d = byId.get(p.getId());
                printer.printRecord(
                        p.getId(), p.getName(), nz(p.getBrand()), nz(p.getSize()), nz(p.getBarcode()),
                        nz(p.getSkuTemplate()),
                        d == null ? "" : nz(d.getGenericName()),
                        d == null ? "" : nz(d.getStrength()),
                        d == null ? "" : nz(d.getDosageForm()),
                        d == null ? "" : nz(d.getPackSize()),
                        d == null ? "" : nz(d.getManufacturer()),
                        d == null ? "" : nz(d.getSuggestedClass()));
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not write the CSV");
        }
        return out.toByteArray();
    }

    /**
     * Sets medicine details from a CSV. A row is matched to a product by {@code id}, then
     * {@code barcode}, then {@code sku_template}. A row with every medicine column blank is
     * skipped, so an exported sheet can be uploaded part filled.
     */
    @Transactional
    public MedicineImportResponse importCsv(String catalogId, byte[] data, boolean dryRun) {
        GlobalCatalog catalog = requireCatalog(catalogId);
        List<Map<String, String>> rows = parse(data);
        if (rows.size() > MAX_IMPORT_ROWS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "File exceeds " + MAX_IMPORT_ROWS + " data rows (" + rows.size() + ")");
        }
        Map<String, GlobalProduct> byId = new HashMap<>();
        Map<String, GlobalProduct> byBarcode = new HashMap<>();
        Map<String, GlobalProduct> bySku = new HashMap<>();
        for (GlobalProduct p : globalProductRepository.findByCatalogId(catalog.getId())) {
            if (GlobalProductStatus.ARCHIVED.equals(p.getStatus())) {
                continue;
            }
            byId.put(p.getId(), p);
            if (blankToNull(p.getBarcode()) != null) {
                byBarcode.putIfAbsent(p.getBarcode().trim(), p);
            }
            if (blankToNull(p.getSkuTemplate()) != null) {
                bySku.putIfAbsent(p.getSkuTemplate().trim().toLowerCase(Locale.ROOT), p);
            }
        }
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        List<MedicineImportSkip> skipped = new ArrayList<>();
        int rowNo = 1;
        for (Map<String, String> row : rows) {
            rowNo++;
            GlobalProduct product = match(row, byId, byBarcode, bySku);
            if (product == null) {
                skipped.add(new MedicineImportSkip(rowNo, "No catalogue product matches this id, barcode or SKU"));
                continue;
            }
            if (!isPharmacyProduct(product)) {
                skipped.add(new MedicineImportSkip(rowNo, "Not a pharmacy catalogue product"));
                continue;
            }
            String generic = cell(row, "generic_name", 255);
            String strength = cell(row, "strength", 64);
            String form = cell(row, "dosage_form", 64);
            String pack = cell(row, "pack_size", 64);
            String maker = cell(row, "manufacturer", 191);
            String suggestedRaw = blankToNull(row.get("suggested_class"));
            if (generic == null && strength == null && form == null && pack == null && maker == null
                    && suggestedRaw == null) {
                skipped.add(new MedicineImportSkip(rowNo, "No medicine details on this row"));
                continue;
            }
            String suggested;
            try {
                suggested = suggestedRaw == null ? null : parseSuggestedClassOrThrow(suggestedRaw).name();
            } catch (IllegalArgumentException ex) {
                skipped.add(new MedicineImportSkip(rowNo, ex.getMessage()));
                continue;
            }
            GlobalProductMedicineDetails d = medicineRepository.findById(product.getId()).orElse(null);
            boolean creating = d == null;
            if (creating) {
                d = new GlobalProductMedicineDetails();
                d.setGlobalProductId(product.getId());
            } else if (Objects.equals(d.getGenericName(), generic)
                    && Objects.equals(d.getStrength(), strength)
                    && Objects.equals(d.getDosageForm(), form)
                    && Objects.equals(d.getPackSize(), pack)
                    && Objects.equals(d.getManufacturer(), maker)
                    && Objects.equals(d.getSuggestedClass(), suggested)) {
                unchanged++;
                continue;
            }
            if (creating) {
                created++;
            } else {
                updated++;
            }
            if (dryRun) {
                continue;
            }
            d.setGenericName(generic);
            d.setStrength(strength);
            d.setDosageForm(form);
            d.setPackSize(pack);
            d.setManufacturer(maker);
            d.setSuggestedClass(suggested);
            medicineRepository.save(d);
        }
        return new MedicineImportResponse(dryRun, rows.size(), created, updated, unchanged, skipped);
    }

    static List<Map<String, String>> parse(byte[] data) {
        if (data == null || data.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file is empty");
        }
        String text = new String(data, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build();
        List<Map<String, String>> rows = new ArrayList<>();
        try (Reader reader = new StringReader(text); CSVParser parser = format.parse(reader)) {
            List<String> headers = parser.getHeaderNames();
            List<String> normalized = headers.stream().map(GlobalCatalogMedicineService::normalizeHeader).toList();
            if (!normalized.contains("id") && !normalized.contains("barcode") && !normalized.contains("sku_template")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "The file needs an id, barcode or sku_template column to match products");
            }
            for (CSVRecord rec : parser) {
                Map<String, String> row = new HashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    String value = i < rec.size() ? rec.get(i) : "";
                    row.putIfAbsent(normalized.get(i), value == null ? "" : value.trim());
                }
                rows.add(row);
            }
        } catch (IOException | IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the CSV: " + ex.getMessage());
        }
        return rows;
    }

    /** OTC, PRESCRIPTION_ONLY or CONTROLLED in any spelling the till accepts; blank means none. */
    static DispensingClass parseSuggestedClassOrThrow(String raw) {
        String cleaned = raw.strip().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (cleaned.equals("POM") || cleaned.equals("PRESCRIPTION")) {
            cleaned = DispensingClass.PRESCRIPTION_ONLY.name();
        }
        if (cleaned.equals("OVER_THE_COUNTER")) {
            cleaned = DispensingClass.OTC.name();
        }
        for (DispensingClass c : DispensingClass.values()) {
            if (c != DispensingClass.UNCLASSIFIED && c.name().equals(cleaned)) {
                return c;
            }
        }
        throw new IllegalArgumentException(
                "Unknown suggested class '" + raw + "'. Use OTC, PRESCRIPTION_ONLY or CONTROLLED");
    }

    private static String parseSuggestedClass(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return parseSuggestedClassOrThrow(raw).name();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    private static GlobalProduct match(
            Map<String, String> row,
            Map<String, GlobalProduct> byId,
            Map<String, GlobalProduct> byBarcode,
            Map<String, GlobalProduct> bySku
    ) {
        String id = blankToNull(row.get("id"));
        if (id != null) {
            return byId.get(id);
        }
        String barcode = blankToNull(row.get("barcode"));
        if (barcode != null && byBarcode.containsKey(barcode)) {
            return byBarcode.get(barcode);
        }
        String sku = blankToNull(row.get("sku_template"));
        return sku == null ? null : bySku.get(sku.toLowerCase(Locale.ROOT));
    }

    private static String cell(Map<String, String> row, String key, int max) {
        String v = blankToNull(row.get(key));
        if (v == null) {
            return null;
        }
        return v.length() > max ? v.substring(0, max) : v;
    }

    private static String normalizeHeader(String raw) {
        return raw == null ? "" : raw.replace("﻿", "").trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    static boolean isPharmacyProduct(GlobalProduct product) {
        return product.getStoreKitId() != null && PHARMACY_KIT.equalsIgnoreCase(product.getStoreKitId().trim());
    }

    private static void requirePharmacyProduct(GlobalProduct product) {
        if (!isPharmacyProduct(product)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only products in the pharmacy catalogue can carry medicine details");
        }
    }

    private GlobalProduct requireProduct(String id) {
        return globalProductRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Global product not found"));
    }

    private GlobalCatalog requireCatalog(String catalogId) {
        if (catalogId == null || catalogId.isBlank()) {
            return globalCatalogRepository.findByCode(DEFAULT_CATALOG_CODE)
                    .or(() -> globalCatalogRepository.findFirstByStatusOrderByVersionDesc(GlobalProductStatus.PUBLISHED))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No global catalog available"));
        }
        String key = catalogId.trim();
        return globalCatalogRepository.findById(key)
                .or(() -> globalCatalogRepository.findByCode(key))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Global catalog not found"));
    }

    private static MedicineTemplateResponse toResponse(GlobalProductMedicineDetails d) {
        return new MedicineTemplateResponse(
                d.getGlobalProductId(), true, d.getGenericName(), d.getStrength(), d.getDosageForm(),
                d.getPackSize(), d.getManufacturer(), d.getSuggestedClass());
    }

    private static MedicineTemplateResponse notMarked(String globalProductId) {
        return new MedicineTemplateResponse(globalProductId, false, null, null, null, null, null, null);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
