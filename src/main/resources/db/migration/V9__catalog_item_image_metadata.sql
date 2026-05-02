-- Optional metadata for item gallery rows (S3 object key is stored after client-side upload).

ALTER TABLE item_images
  ADD COLUMN content_type VARCHAR(128) NULL,
  ADD COLUMN alt_text VARCHAR(500) NULL;
