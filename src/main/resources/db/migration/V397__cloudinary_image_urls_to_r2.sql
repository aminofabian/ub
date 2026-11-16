-- Rewrite stored Cloudinary image URLs to their Cloudflare R2 mirror.
--
-- Media moved from Cloudinary (cloud `dzlv4lat4`) to R2, and every object was
-- mirrored to R2 under the same key (the Cloudinary path after
-- `/image/upload/{transforms/}v<version>/`). But rows written before the switch
-- still hold `https://res.cloudinary.com/...` URLs, and the read paths serve
-- stored URLs verbatim (no runtime rewrite). Cloudinary now answers 401 for the
-- whole cloud, so every pre-switch image is broken on storefronts and in the
-- POS while post-switch (R2) images load.
--
-- Rule applied to every affected column (verified against the live bucket):
--   https://res.cloudinary.com/dzlv4lat4/image/upload/[e_trim/...]v123/<key>
--   -> <r2_public_base_url>/<key>
-- Transform segments and the version are dropped; the key and extension are
-- kept unchanged. `cloudinary_public_id` / `*_public_id` columns are left as
-- they are: deletes still resolve them (R2LegacyObjectRemover matches
-- `<publicId>.*`), and `provider` already means "cloud-hosted" for R2 rows too.
--
-- Scope and safety:
-- * Only URLs on cloud `dzlv4lat4` are touched (that is the mirrored cloud);
--   any other host or cloud is left alone.
-- * The R2 base comes from platform_media_storage_settings. When it is not set
--   (fresh install, desktop/local mode) every UPDATE matches zero rows — the
--   migration is a no-op rather than writing "NULL/<key>".
-- * Every UPDATE is bounded by a LIKE + REGEXP on the column it rewrites;
--   rows already on R2 are not matched.
-- * businesses.settings (tenant branding JSON: logoUrl, logoDarkUrl,
--   faviconUrl, appIconUrl, ogImageUrl, heroBanners[].url) is rewritten as text
--   because MariaDB has no JSON type (see V143). Only URL substrings change, so
--   the document stays valid JSON.
-- * MySQL + MariaDB compatible: REGEXP_REPLACE exists in MySQL 8.0+ (ICU) and
--   MariaDB 10.0.5+ (PCRE); the pattern uses only shared syntax.
-- * Re-runnable by construction (nothing matches after the first run). No
--   down-migration: a reverse rewrite would be the same statements with the
--   bases swapped, and the Cloudinary copies are not reachable anyway.

SET @r2_base := (
    SELECT TRIM(TRAILING '/' FROM r2_public_base_url)
      FROM platform_media_storage_settings
     WHERE r2_public_base_url IS NOT NULL AND TRIM(r2_public_base_url) <> ''
     ORDER BY updated_at DESC
     LIMIT 1
);

-- LIKE prefilter (cheap) and the regex that strips everything up to the key.
SET @cld_like := 'https://res.cloudinary.com/dzlv4lat4/%';
SET @cld_any  := '%res.cloudinary.com/dzlv4lat4/%';
SET @strip    := 'https://res\\.cloudinary\\.com/dzlv4lat4/[a-z]+/upload/(?:[^/]+/)*?v[0-9]+/';
SET @r2_prefix := CONCAT(@r2_base, '/');

-- ---------------------------------------------------------------------------
-- Item gallery images (storefront + POS product photos)
-- ---------------------------------------------------------------------------
UPDATE item_images
   SET secure_url = REGEXP_REPLACE(secure_url, @strip, @r2_prefix)
 WHERE @r2_base IS NOT NULL
   AND secure_url LIKE @cld_like
   AND secure_url REGEXP @strip;

UPDATE item_images
   SET s3_key = REGEXP_REPLACE(s3_key, @strip, @r2_prefix)
 WHERE @r2_base IS NOT NULL
   AND s3_key LIKE @cld_like
   AND s3_key REGEXP @strip;

-- Desktop mirrors only (NULL on the cloud): the till compares this on "Sync
-- now" to detect a changed photo, so pointing it at R2 makes it re-download
-- from a URL that works.
UPDATE item_images
   SET cloud_url = REGEXP_REPLACE(cloud_url, @strip, @r2_prefix)
 WHERE @r2_base IS NOT NULL
   AND cloud_url LIKE @cld_like
   AND cloud_url REGEXP @strip;

-- ---------------------------------------------------------------------------
-- Category images (aisle tiles)
-- ---------------------------------------------------------------------------
UPDATE category_images
   SET secure_url = REGEXP_REPLACE(secure_url, @strip, @r2_prefix)
 WHERE @r2_base IS NOT NULL
   AND secure_url LIKE @cld_like
   AND secure_url REGEXP @strip;

UPDATE category_images
   SET s3_key = REGEXP_REPLACE(s3_key, @strip, @r2_prefix)
 WHERE @r2_base IS NOT NULL
   AND s3_key LIKE @cld_like
   AND s3_key REGEXP @strip;

-- ---------------------------------------------------------------------------
-- Global catalogue
-- ---------------------------------------------------------------------------
UPDATE global_product_images
   SET image_url = REGEXP_REPLACE(image_url, @strip, @r2_prefix)
 WHERE @r2_base IS NOT NULL
   AND image_url LIKE @cld_like
   AND image_url REGEXP @strip;

UPDATE global_products
   SET image_url = REGEXP_REPLACE(image_url, @strip, @r2_prefix)
 WHERE @r2_base IS NOT NULL
   AND image_url LIKE @cld_like
   AND image_url REGEXP @strip;

UPDATE global_categories
   SET image_url = REGEXP_REPLACE(image_url, @strip, @r2_prefix)
 WHERE @r2_base IS NOT NULL
   AND image_url LIKE @cld_like
   AND image_url REGEXP @strip;

-- ---------------------------------------------------------------------------
-- Restaurant menu items and service offerings (item-type pictures)
-- ---------------------------------------------------------------------------
UPDATE menu_items
   SET image_url = REGEXP_REPLACE(image_url, @strip, @r2_prefix)
 WHERE @r2_base IS NOT NULL
   AND image_url LIKE @cld_like
   AND image_url REGEXP @strip;

UPDATE service_offerings
   SET image_url = REGEXP_REPLACE(image_url, @strip, @r2_prefix)
 WHERE @r2_base IS NOT NULL
   AND image_url LIKE @cld_like
   AND image_url REGEXP @strip;

-- ---------------------------------------------------------------------------
-- Support attachments, platform email logo, staff photos
-- ---------------------------------------------------------------------------
UPDATE support_messages
   SET attachment_url = REGEXP_REPLACE(attachment_url, @strip, @r2_prefix)
 WHERE @r2_base IS NOT NULL
   AND attachment_url LIKE @cld_like
   AND attachment_url REGEXP @strip;

UPDATE platform_branding_settings
   SET email_logo_url = REGEXP_REPLACE(email_logo_url, @strip, @r2_prefix)
 WHERE @r2_base IS NOT NULL
   AND email_logo_url LIKE @cld_like
   AND email_logo_url REGEXP @strip;

UPDATE staff_profiles
   SET photo_url = REGEXP_REPLACE(photo_url, @strip, @r2_prefix)
 WHERE @r2_base IS NOT NULL
   AND photo_url LIKE @cld_like
   AND photo_url REGEXP @strip;

-- ---------------------------------------------------------------------------
-- Tenant branding, stored as JSON text in businesses.settings
-- ---------------------------------------------------------------------------
UPDATE businesses
   SET settings = REGEXP_REPLACE(settings, @strip, @r2_prefix)
 WHERE @r2_base IS NOT NULL
   AND settings LIKE @cld_any;
