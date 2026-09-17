package zelisline.ub.desktop.application;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import zelisline.ub.catalog.domain.ItemImage;
import zelisline.ub.catalog.domain.ItemImageStorageProvider;
import zelisline.ub.catalog.repository.ItemImageRepository;
import zelisline.ub.desktop.api.dto.MasterDataSnapshot;
import zelisline.ub.platform.media.CloudinaryImageService;
import zelisline.ub.platform.media.CloudinaryUploadResult;
import zelisline.ub.platform.media.MediaStore;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Desktop-side mirror of the online shop's product photos <em>and</em> store
 * branding assets (connect + Settings → Sync now).
 *
 * <p>Phase 1 ({@link #upsertMetadata(String, List)}) runs inside the main sync
 * transaction: it upserts the {@code ItemImage} rows from the snapshot so the
 * catalog metadata is consistent, and keeps the cloud {@code secureUrl} until
 * the local file exists (online fallback). Phase 2 ({@link #rehost(String, List)})
 * runs after the transaction: it downloads each pending file and re-hosts it in
 * the local media store ({@code APP_DATA/media}), then points the row at the
 * local {@code /media/...} URL so the till renders photos offline.
 *
 * <p>Store branding (logo, favicon, app icon, OG image, hero banners) lives in
 * {@code businesses.settings.branding}. Sync merges the cloud settings JSON, so
 * those URLs arrive as Cloudinary links; {@link #restoreLocalBrandingUrls(String)}
 * puts working local copies back, and {@link #rehostBranding} downloads any
 * gaps so the shop identity stays visible offline.
 *
 * <p>Change detection uses the {@code cloud_url} column plus an on-disk check:
 * an image is re-downloaded when the cloud URL changed <em>or</em> the local
 * file is missing. Successful local {@code /media/...} URLs are preserved
 * across syncs so photos keep working offline.
 */
@Service
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopMediaSyncService {

    private static final Logger log = LoggerFactory.getLogger(DesktopMediaSyncService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Scalar branding URL fields under {@code settings.branding}. */
    private static final List<String> BRANDING_URL_FIELDS = List.of(
        "logoUrl",
        "logoDarkUrl",
        "faviconUrl",
        "appIconUrl",
        "ogImage"
    );

    private final ItemImageRepository itemImageRepository;
    private final BusinessRepository businessRepository;
    private final MediaStore mediaStore;

    /** Parallel downloads (photos are small and independent). */
    private final ExecutorService mediaPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "desktop-media-sync");
        t.setDaemon(true);
        return t;
    });
    /** Single-thread orchestrator so the caller never blocks on the pool. */
    private final ExecutorService mediaOrchestrator = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "desktop-media-orchestrator");
        t.setDaemon(true);
        return t;
    });

    /** Live progress for the Settings → Sync now UI. */
    private final AtomicInteger mediaTotal = new AtomicInteger();
    private final AtomicInteger mediaDone = new AtomicInteger();
    private volatile boolean mediaRunning = false;

    public record MediaStatus(boolean downloading, int total, int done) {}

    public MediaStatus status() {
        return new MediaStatus(mediaRunning, mediaTotal.get(), mediaDone.get());
    }

    /** An image whose local file is missing or stale and needs re-hosting. */
    public record PendingImage(String id, String itemId, String cloudUrl, String format) {}

    /**
     * Upsert image metadata from the snapshot. Call inside the sync transaction
     * (item rows must already exist — there is an FK to {@code items}).
     */
    public List<PendingImage> upsertMetadata(String localId, List<MasterDataSnapshot.ImageData> images) {
        if (images == null) {
            return List.of();
        }
        List<PendingImage> pending = new ArrayList<>();
        for (MasterDataSnapshot.ImageData d : images) {
            if (d.id() == null || d.id().isBlank() || d.itemId() == null || d.itemId().isBlank()) {
                continue;
            }
            ItemImage img = itemImageRepository
                .findByIdAndItemId(d.id(), d.itemId())
                .orElseGet(() -> {
                    ItemImage created = new ItemImage();
                    created.setId(d.id());
                    created.setItemId(d.itemId());
                    created.setProvider(ItemImageStorageProvider.LEGACY);
                    return created;
                });

            String cloudUrl = d.secureUrl() == null ? null : d.secureUrl().trim();
            String previousCloudUrl = img.getCloudUrl();
            boolean cloudChanged = cloudUrl != null
                && isRemote(cloudUrl)
                && !cloudUrl.equals(previousCloudUrl);
            boolean hasLocalCopy = hasUsableLocalCopy(img);
            // Re-download when the cloud URL changed OR we never got a local
            // file (failed download / wiped media folder). Previously we only
            // queued on URL change — after the first metadata upsert every
            // Sync skipped photos that were still pointing at Cloudinary.
            boolean needsDownload = cloudUrl != null
                && isRemote(cloudUrl)
                && (cloudChanged || !hasLocalCopy);

            applyMetadata(img, d, cloudUrl, hasLocalCopy && !cloudChanged);
            itemImageRepository.save(img);

            if (needsDownload) {
                pending.add(new PendingImage(img.getId(), d.itemId(), cloudUrl, d.format()));
            }
        }
        if (!pending.isEmpty()) {
            log.info("[DesktopSync] {} product photo(s) need a local copy", pending.size());
        }
        return pending;
    }

    /**
     * True when the row already points at a local {@code /media/...} URL whose
     * file is still on disk. Used to preserve offline URLs across syncs and to
     * retry downloads after a wipe / failed re-host.
     */
    private boolean hasUsableLocalCopy(ItemImage img) {
        String secureUrl = img.getSecureUrl();
        if (secureUrl == null || secureUrl.isBlank() || isRemote(secureUrl)) {
            return false;
        }
        String publicId = img.getCloudinaryPublicId();
        if (publicId == null || publicId.isBlank()) {
            publicId = publicIdFromLocalUrl(secureUrl);
        }
        return publicId != null && mediaStore.hasLocalObject(publicId);
    }

    private static String publicIdFromLocalUrl(String secureUrl) {
        String path = secureUrl.trim();
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        // "/media/ub/.../file.jpg" → "ub/.../file.jpg"
        if (path.startsWith("/media/")) {
            return path.substring("/media/".length());
        }
        if (path.startsWith("media/")) {
            return path.substring("media/".length());
        }
        return null;
    }

    private static void applyMetadata(
            ItemImage img,
            MasterDataSnapshot.ImageData d,
            String cloudUrl,
            boolean keepLocalSecureUrl) {
        img.setContentType(d.contentType());
        img.setSortOrder(d.sortOrder());
        img.setFormat(d.format());
        img.setAltText(d.altText());
        img.setWidth(d.width());
        img.setHeight(d.height());
        img.setBytes(d.bytes());
        img.setCloudUrl(cloudUrl);
        // Never clobber a working local /media URL with the cloud CDN link —
        // that was the offline-photo bug (every Sync reset secureUrl).
        if (!keepLocalSecureUrl) {
            img.setSecureUrl(cloudUrl);
            img.setCloudinaryPublicId(null);
            img.setProvider(ItemImageStorageProvider.LEGACY);
        }
    }

    /**
     * Kick off a background re-host so connect / Sync-now return immediately.
     * Photos and store branding appear progressively; rows keep cloud URLs until
     * each local copy lands (online fallback).
     */
    public void rehostAsync(String localId, List<PendingImage> pending) {
        rehostAsync(localId, pending, List.of());
    }

    public void rehostAsync(
            String localId,
            List<PendingImage> pendingImages,
            List<PendingBrandingAsset> pendingBranding) {
        List<PendingImage> images = pendingImages == null ? List.of() : List.copyOf(pendingImages);
        List<PendingBrandingAsset> branding =
            pendingBranding == null ? List.of() : List.copyOf(pendingBranding);
        if (images.isEmpty() && branding.isEmpty()) {
            return;
        }
        mediaOrchestrator.execute(() -> {
            rehost(localId, images);
            rehostBranding(localId, branding);
        });
    }

    /** A store branding asset (logo / favicon / banner) that needs a local copy. */
    public record PendingBrandingAsset(String slot, String cloudUrl) {}

    /**
     * After a master-data merge puts Cloudinary URLs back into
     * {@code settings.branding}, restore any previously re-hosted local
     * {@code /media/...} URLs whose cloud source is unchanged and still on disk.
     */
    public String restoreLocalBrandingUrls(String settings) {
        if (settings == null || settings.isBlank()) {
            return settings;
        }
        try {
            ObjectNode root = (ObjectNode) JSON.readTree(settings);
            JsonNode brandingNode = root.path("branding");
            if (!brandingNode.isObject()) {
                return settings;
            }
            ObjectNode branding = (ObjectNode) brandingNode;
            ObjectNode assets = root.path("desktop").path("brandingAssets").isObject()
                ? (ObjectNode) root.path("desktop").path("brandingAssets")
                : JSON.createObjectNode();
            boolean changed = false;

            for (String field : BRANDING_URL_FIELDS) {
                if (restoreScalar(branding, assets, field)) {
                    changed = true;
                }
            }
            if (restoreHeroBanners(branding, assets)) {
                changed = true;
            }
            return changed ? JSON.writeValueAsString(root) : settings;
        } catch (Exception e) {
            log.warn("[DesktopSync] could not restore local branding URLs: {}", e.getMessage());
            return settings;
        }
    }

    /**
     * Collect branding URLs that are still remote (need download) after restore.
     */
    public List<PendingBrandingAsset> collectPendingBranding(String settings) {
        if (settings == null || settings.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = JSON.readTree(settings);
            JsonNode branding = root.path("branding");
            if (!branding.isObject()) {
                return List.of();
            }
            List<PendingBrandingAsset> pending = new ArrayList<>();
            for (String field : BRANDING_URL_FIELDS) {
                String url = textOrNull(branding.get(field));
                if (url != null && isRemote(url)) {
                    pending.add(new PendingBrandingAsset(field, url));
                }
            }
            JsonNode banners = branding.path("heroBanners");
            if (banners.isArray()) {
                for (int i = 0; i < banners.size(); i++) {
                    JsonNode entry = banners.get(i);
                    String url = entry != null && entry.isObject()
                        ? textOrNull(entry.get("url"))
                        : null;
                    if (url != null && isRemote(url)) {
                        pending.add(new PendingBrandingAsset("heroBanners[" + i + "]", url));
                    }
                }
            }
            if (!pending.isEmpty()) {
                log.info("[DesktopSync] {} store branding asset(s) need a local copy", pending.size());
            }
            return pending;
        } catch (Exception e) {
            log.warn("[DesktopSync] could not collect branding assets: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Download store branding assets and rewrite {@code settings.branding} to
     * local {@code /media/...} URLs. Best-effort; failures leave the cloud URL.
     */
    public int rehostBranding(String localId, List<PendingBrandingAsset> pending) {
        if (pending == null || pending.isEmpty()) {
            return 0;
        }
        // Branding rewrites touch one settings JSON — do them serially.
        int before = mediaTotal.get();
        if (!mediaRunning) {
            mediaRunning = true;
            mediaTotal.set(pending.size());
            mediaDone.set(0);
        } else {
            mediaTotal.addAndGet(pending.size());
        }
        int done = 0;
        try {
            for (PendingBrandingAsset asset : pending) {
                if (rehostBrandingOne(localId, asset)) {
                    done++;
                    mediaDone.incrementAndGet();
                }
            }
        } finally {
            if (before == 0) {
                mediaRunning = false;
            }
        }
        log.info("[DesktopSync] re-hosted {} store branding asset(s) locally", done);
        return done;
    }

    private boolean rehostBrandingOne(String localId, PendingBrandingAsset asset) {
        try {
            byte[] bytes = download(asset.cloudUrl());
            if (bytes == null || bytes.length == 0) {
                throw new IllegalStateException("empty download");
            }
            String folder = "ub/" + localId + "/branding/" + sanitizeSlot(asset.slot());
            CloudinaryUploadResult result = mediaStore.uploadImageToFolder(
                bytes,
                filenameFor(asset.cloudUrl(), null),
                folder
            );
            return rewriteBrandingUrl(localId, asset.slot(), asset.cloudUrl(), result);
        } catch (Exception e) {
            log.warn(
                "[DesktopSync] could not re-host branding {} from {}: {}",
                asset.slot(),
                asset.cloudUrl(),
                e.getMessage()
            );
            return false;
        }
    }

    private boolean rewriteBrandingUrl(
            String localId,
            String slot,
            String cloudUrl,
            CloudinaryUploadResult uploaded) {
        Business business = businessRepository.findByIdAndDeletedAtIsNull(localId).orElse(null);
        if (business == null) {
            return false;
        }
        try {
            ObjectNode root = business.getSettings() == null || business.getSettings().isBlank()
                ? JSON.createObjectNode()
                : (ObjectNode) JSON.readTree(business.getSettings());
            ObjectNode branding = root.withObject("/branding");
            ObjectNode desktop = root.withObject("/desktop");
            ObjectNode assets = desktop.withObject("/brandingAssets");

            String localUrl = uploaded.secureUrl();
            String publicId = uploaded.publicId();

            if (slot.startsWith("heroBanners[")) {
                int idx = parseHeroIndex(slot);
                ArrayNode banners = branding.withArray("heroBanners");
                if (idx < 0 || idx >= banners.size() || !banners.get(idx).isObject()) {
                    return false;
                }
                ObjectNode entry = (ObjectNode) banners.get(idx);
                entry.put("url", localUrl);
                if (publicId != null) {
                    entry.put("publicId", publicId);
                }
            } else {
                branding.put(slot, localUrl);
                String publicIdField = publicIdFieldFor(slot);
                if (publicIdField != null && publicId != null) {
                    branding.put(publicIdField, publicId);
                }
            }

            ObjectNode track = JSON.createObjectNode();
            track.put("cloud", cloudUrl);
            track.put("local", localUrl);
            if (publicId != null) {
                track.put("publicId", publicId);
            }
            assets.set(slot, track);

            business.setSettings(JSON.writeValueAsString(root));
            businessRepository.save(business);
            return true;
        } catch (Exception e) {
            log.warn("[DesktopSync] could not rewrite branding {}: {}", slot, e.getMessage());
            return false;
        }
    }

    private boolean restoreScalar(ObjectNode branding, ObjectNode assets, String field) {
        String cloudNow = textOrNull(branding.get(field));
        if (cloudNow == null || !isRemote(cloudNow)) {
            return false;
        }
        JsonNode tracked = assets.path(field);
        if (!tracked.isObject()) {
            return false;
        }
        String trackedCloud = textOrNull(tracked.get("cloud"));
        String local = textOrNull(tracked.get("local"));
        String publicId = textOrNull(tracked.get("publicId"));
        if (trackedCloud == null || local == null || !trackedCloud.equals(cloudNow)) {
            return false;
        }
        if (publicId == null) {
            publicId = publicIdFromLocalUrl(local);
        }
        if (publicId == null || !mediaStore.hasLocalObject(publicId)) {
            return false;
        }
        branding.put(field, local);
        String publicIdField = publicIdFieldFor(field);
        if (publicIdField != null) {
            branding.put(publicIdField, publicId);
        }
        return true;
    }

    private boolean restoreHeroBanners(ObjectNode branding, ObjectNode assets) {
        JsonNode bannersNode = branding.get("heroBanners");
        if (bannersNode == null || !bannersNode.isArray()) {
            return false;
        }
        ArrayNode banners = (ArrayNode) bannersNode;
        boolean changed = false;
        for (int i = 0; i < banners.size(); i++) {
            JsonNode entryNode = banners.get(i);
            if (entryNode == null || !entryNode.isObject()) {
                continue;
            }
            ObjectNode entry = (ObjectNode) entryNode;
            String cloudNow = textOrNull(entry.get("url"));
            if (cloudNow == null || !isRemote(cloudNow)) {
                continue;
            }
            String slot = "heroBanners[" + i + "]";
            JsonNode tracked = assets.path(slot);
            if (!tracked.isObject()) {
                continue;
            }
            String trackedCloud = textOrNull(tracked.get("cloud"));
            String local = textOrNull(tracked.get("local"));
            String publicId = textOrNull(tracked.get("publicId"));
            if (trackedCloud == null || local == null || !trackedCloud.equals(cloudNow)) {
                continue;
            }
            if (publicId == null) {
                publicId = publicIdFromLocalUrl(local);
            }
            if (publicId == null || !mediaStore.hasLocalObject(publicId)) {
                continue;
            }
            entry.put("url", local);
            entry.put("publicId", publicId);
            changed = true;
        }
        return changed;
    }

    private static String publicIdFieldFor(String urlField) {
        return switch (urlField) {
            case "logoUrl" -> "logoPublicId";
            case "logoDarkUrl" -> "logoDarkPublicId";
            case "faviconUrl" -> "faviconPublicId";
            case "appIconUrl" -> "appIconPublicId";
            case "ogImage" -> "ogImagePublicId";
            default -> null;
        };
    }

    private static int parseHeroIndex(String slot) {
        try {
            int start = slot.indexOf('[') + 1;
            int end = slot.indexOf(']');
            return Integer.parseInt(slot.substring(start, end));
        } catch (Exception e) {
            return -1;
        }
    }

    private static String sanitizeSlot(String slot) {
        if (slot == null || slot.isBlank()) {
            return "asset";
        }
        return slot.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isTextual()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Download and re-host pending images. Runs outside the main transaction so
     * network I/O never holds the DB transaction open. Best-effort: a failed
     * download leaves the cloud URL in place (online fallback).
     *
     * @return number of images successfully re-hosted
     */
    public int rehost(String localId, List<PendingImage> pending) {
        if (pending == null || pending.isEmpty()) {
            return 0;
        }
        mediaRunning = true;
        mediaTotal.set(pending.size());
        mediaDone.set(0);
        CountDownLatch latch = new CountDownLatch(pending.size());
        AtomicInteger done = new AtomicInteger();
        for (PendingImage p : pending) {
            mediaPool.execute(() -> {
                try {
                    if (rehostOne(localId, p)) {
                        done.incrementAndGet();
                        mediaDone.set(done.get());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            mediaRunning = false;
        }
        log.info("[DesktopSync] re-hosted {} product photo(s) locally", done.get());
        return done.get();
    }

    private boolean rehostOne(String localId, PendingImage p) {
        try {
            byte[] bytes = download(p.cloudUrl());
            if (bytes == null || bytes.length == 0) {
                throw new IllegalStateException("empty download");
            }
            String folder = CloudinaryImageService.folderItems(localId, p.itemId());
            CloudinaryUploadResult result = mediaStore.uploadImageToFolder(
                bytes,
                filenameFor(p.cloudUrl(), p.format()),
                folder
            );
            ItemImage img = itemImageRepository.findById(p.id()).orElse(null);
            if (img == null) {
                log.warn("[DesktopSync] image row {} vanished before re-host", p.id());
                return false;
            }
            img.setSecureUrl(result.secureUrl());
            img.setCloudinaryPublicId(result.publicId());
            img.setProvider(ItemImageStorageProvider.LEGACY);
            if (result.bytes() != null) {
                img.setBytes(result.bytes());
            }
            if (result.format() != null) {
                img.setFormat(result.format());
            }
            if (result.contentType() != null) {
                img.setContentType(result.contentType());
            }
            if (img.getWidth() == null && result.width() != null) {
                img.setWidth(result.width());
            }
            if (img.getHeight() == null && result.height() != null) {
                img.setHeight(result.height());
            }
            itemImageRepository.save(img);
            return true;
        } catch (Exception e) {
            log.warn(
                "[DesktopSync] could not re-host image {} from {}: {}",
                p.id(),
                p.cloudUrl(),
                e.getMessage()
            );
            return false;
        }
    }

    private static byte[] download(String url) throws Exception {
        return RestClient.create()
            .get()
            .uri(url)
            .retrieve()
            .body(byte[].class);
    }

    private static boolean isRemote(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    private static String filenameFor(String url, String format) {
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        if (name.isBlank()) {
            name = "image";
        }
        String ext = extensionOf(name);
        if (ext.isEmpty()) {
            ext = format == null || format.isBlank() ? "jpg" : format.toLowerCase();
            name = name + "." + ext;
        }
        return name;
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase();
    }
}
