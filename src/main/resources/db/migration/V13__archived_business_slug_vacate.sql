-- Archived businesses kept UNIQUE(slug) on the column; reclaim human slugs by
-- appending -{id} (matches BusinessDeletionService.archivedSlug).
UPDATE businesses
   SET slug = CASE
     WHEN CHAR_LENGTH(slug) + 37 <= 191 THEN CONCAT(slug, '-', id)
     ELSE CONCAT(LEFT(slug, 191 - 37), '-', id)
   END
 WHERE deleted_at IS NOT NULL
   AND NOT (
     CHAR_LENGTH(slug) >= 37
     AND RIGHT(slug, 37) = CONCAT('-', id)
   );
