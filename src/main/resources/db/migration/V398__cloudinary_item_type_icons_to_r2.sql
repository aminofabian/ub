-- Forward-fix for V397: item_types.icon was missed.
--
-- V397 rewrote every stored Cloudinary image URL to its R2 mirror but did not
-- cover item_types.icon, which holds the uploaded department / item-type
-- picture URL (folder `ub/<business>/item-types/<id>/`). Those tiles are the
-- last images still answering 401 on storefronts and in the POS.
--
-- Same rule, same guards as V397 (see its header): only cloud `dzlv4lat4`,
-- bounded by LIKE + REGEXP, R2 base read from platform_media_storage_settings,
-- no-op when it is not set, idempotent. MySQL + MariaDB compatible.

SET @r2_base := (
    SELECT TRIM(TRAILING '/' FROM r2_public_base_url)
      FROM platform_media_storage_settings
     WHERE r2_public_base_url IS NOT NULL AND TRIM(r2_public_base_url) <> ''
     ORDER BY updated_at DESC
     LIMIT 1
);
SET @cld_like  := 'https://res.cloudinary.com/dzlv4lat4/%';
SET @strip     := 'https://res\\.cloudinary\\.com/dzlv4lat4/[a-z]+/upload/(?:[^/]+/)*?v[0-9]+/';
SET @r2_prefix := CONCAT(@r2_base, '/');

UPDATE item_types
   SET icon = REGEXP_REPLACE(icon, @strip, @r2_prefix)
 WHERE @r2_base IS NOT NULL
   AND icon LIKE @cld_like
   AND icon REGEXP @strip;
