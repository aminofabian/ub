-- Tenant item media paths are not portable in the platform global catalog.

UPDATE global_products
   SET image_url = NULL
 WHERE image_url LIKE '/api/media/%';
