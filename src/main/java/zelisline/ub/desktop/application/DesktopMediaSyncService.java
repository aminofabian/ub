package zelisline.ub.desktop.application;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
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

/**
 * Desktop-side mirror of the online shop's product photos (connect + Settings →
 * Sync now).
 *
 * <p>Phase 1 ({@link #upsertMetadata(String, List)}) runs inside the main sync
 * transaction: it upserts the {@code ItemImage} rows from the snapshot so the
 * catalog metadata is consistent, and keeps the cloud {@code secureUrl} until
 * the local file exists (online fallback). Phase 2 ({@link #rehost(String, List)})
 * runs after the transaction: it downloads each pending file and re-hosts it in
 * the local media store ({@code APP_DATA/media}), then points the row at the
 * local {@code /media/...} URL so the till renders photos offline.
 *
 * <p>Change detection uses the {@code cloud_url} column: an image is re-downloaded
 * only when the row is new or the cloud URL changed (HQ wins for master data).
 * Download failures are non-fatal — the row keeps the cloud URL, so photos still
 * load while the machine is online.
 */
@Service
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopMediaSyncService {

    private static final Logger log = LoggerFactory.getLogger(DesktopMediaSyncService.class);

    private final ItemImageRepository itemImageRepository;
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
            boolean needsDownload = cloudUrl != null
                && isRemote(cloudUrl)
                && !cloudUrl.equals(img.getCloudUrl());

            applyMetadata(img, d, cloudUrl);
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

    private static void applyMetadata(ItemImage img, MasterDataSnapshot.ImageData d, String cloudUrl) {
        img.setContentType(d.contentType());
        img.setSortOrder(d.sortOrder());
        img.setFormat(d.format());
        img.setAltText(d.altText());
        img.setWidth(d.width());
        img.setHeight(d.height());
        img.setBytes(d.bytes());
        img.setCloudUrl(cloudUrl);
        img.setSecureUrl(cloudUrl);
        // Files are re-hosted locally — drop cloud-only storage references so a
        // later local destroy cannot resolve a Cloudinary path.
        img.setCloudinaryPublicId(null);
        img.setProvider(ItemImageStorageProvider.LEGACY);
    }

    /**
     * Kick off a background re-host so connect / Sync-now return immediately.
     * Photos appear progressively; the item rows keep their cloud URLs until
     * each local copy lands (online fallback).
     */
    public void rehostAsync(String localId, List<PendingImage> pending) {
        if (pending == null || pending.isEmpty()) {
            return;
        }
        List<PendingImage> snapshot = List.copyOf(pending);
        mediaOrchestrator.execute(() -> rehost(localId, snapshot));
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
