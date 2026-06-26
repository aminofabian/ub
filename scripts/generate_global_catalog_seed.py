#!/usr/bin/env python3
"""Generate Flyway SQL seed for global catalog from legacy import CSVs.

Source files (repo root relative):
  frontend/public/imports/categories.csv
  frontend/public/imports/items.csv
  frontend/public/imports/selling_prices.csv
  frontend/public/imports/buying_prices.csv

Usage:
  # Initial seed (new databases):
  python3 backend/scripts/generate_global_catalog_seed.py > backend/src/main/resources/db/migration/V122__global_catalog_seed_default.sql

  # Reseed after V122 already applied (replaces hand-written catalog in DB):
  python3 backend/scripts/generate_global_catalog_seed.py --reseed > backend/src/main/resources/db/migration/V123__global_catalog_reseed_from_imports.sql
"""

from __future__ import annotations

import argparse
import csv
import re
import sys
from collections import defaultdict
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
IMPORTS = REPO_ROOT / "frontend" / "public" / "imports"

# Primary tenant in the legacy export (largest real shop dataset).
SOURCE_BUSINESS_ID = "7fcae206-6c1c-4c32-81a2-109fe0015042"
CATALOG_ID = "00000000-0000-0000-0000-000000000001"

PACK_MINI_MART = "00000000-0000-0000-0000-000000010000"
PACK_BEVERAGES = "00000000-0000-0000-0000-000000010001"
PACK_GROCERY = "00000000-0000-0000-0000-000000010002"

BEVERAGE_CAT_RE = re.compile(
    r"beverage|juice|water|milk|soda|soft\s*drink|drink|tea|coffee|coke|fanta|sprite",
    re.I,
)
GROCERY_CAT_RE = re.compile(
    r"maize|flour|sugar|rice|oil|cereal|grain|staple|pasta|spice|baking|porridge|uji",
    re.I,
)


def slugify(name: str) -> str:
    s = name.lower().strip()
    s = re.sub(r"[^a-z0-9]+", "-", s)
    s = s.strip("-")
    return (s or "category")[:191]


MAX_PRICE = 1_000_000.0
MAX_STOCK_LEVEL = 100_000.0


def normalize_global_image_url(raw: str | None) -> str | None:
    """Global catalog images must be portable URLs, not tenant media paths."""
    if raw is None or not raw.strip():
        return None
    url = raw.strip()
    if url.startswith("/api/media/"):
        return None
    if url.startswith("http://") or url.startswith("https://"):
        return url[:2048]
    return None


def sql_str(value: str | None) -> str:
    if value is None or value == "":
        return "NULL"
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def sql_dec(value: str | float | None, *, max_value: float = MAX_PRICE) -> str:
    if value is None or value == "":
        return "NULL"
    try:
        amount = float(value)
    except (TypeError, ValueError):
        return "NULL"
    if amount <= 0 or amount > max_value:
        return "NULL"
    return f"{amount:.2f}"


def sql_dec4(value: str | float | None, *, max_value: float = MAX_STOCK_LEVEL) -> str:
    if value is None or value == "":
        return "NULL"
    try:
        amount = float(value)
    except (TypeError, ValueError):
        return "NULL"
    if amount <= 0 or amount > max_value:
        return "NULL"
    return f"{amount:.4f}"


def map_unit_type(raw: str | None) -> str:
    unit = (raw or "each").lower().strip()
    aliases = {
        "piece": "each",
        "pcs": "each",
        "pc": "each",
        "ml": "each",
        "litre": "each",
        "liter": "each",
    }
    mapped = aliases.get(unit, unit)
    return mapped if mapped in {"each", "kg", "g", "l", "ml"} else "each"


def map_item_type_key(raw: str | None) -> str:
    key = (raw or "goods").lower().strip()
    return "goods" if key in {"retail", "cereals", "grocery", "goods"} else "goods"


def load_categories() -> dict[str, dict[str, str]]:
    categories: dict[str, dict[str, str]] = {}
    with (IMPORTS / "categories.csv").open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            if row["business_id"] != SOURCE_BUSINESS_ID or row.get("active") != "1":
                continue
            categories[row["id"]] = row
    return categories


def load_latest_prices(path: str, item_field: str = "item_id") -> dict[str, str]:
    latest: dict[str, tuple[int, str]] = {}
    with (IMPORTS / path).open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            item_id = row[item_field]
            effective_from = int(row.get("effective_from") or 0)
            if item_id not in latest or effective_from > latest[item_id][0]:
                latest[item_id] = (effective_from, row["price"])
    return {item_id: price for item_id, (_, price) in latest.items()}


def parse_product_row(
    row: dict[str, str],
    categories: dict[str, dict[str, str]],
    sell_prices: dict[str, str],
    buy_prices: dict[str, str],
) -> dict | None:
    if row["business_id"] != SOURCE_BUSINESS_ID or row.get("active") != "1":
        return None

    category_id = (row.get("category_id") or "").strip()
    if category_id and category_id not in categories:
        category_id = ""

    item_id = row["id"]
    name = (row.get("name") or "").strip()
    if not name:
        return None

    variant_name = (row.get("variant_name") or "").strip()
    brand = ""
    size = variant_name
    if " - " in name:
        left, right = name.split(" - ", 1)
        brand = left.strip()
        if not size:
            size = right.strip()

    barcode = (row.get("barcode") or "").strip()
    if row.get("barcode_exempt") == "1":
        barcode = ""

    selling_price = sell_prices.get(item_id)
    if not selling_price or float(selling_price) <= 0:
        current_sell = row.get("current_sell_price")
        if current_sell and float(current_sell) > 0:
            selling_price = current_sell

    buying_price = buy_prices.get(item_id)

    return {
        "id": item_id,
        "category_id": category_id or None,
        "name": name[:500],
        "brand": brand[:255] if brand else None,
        "size": size[:50] if size else None,
        "barcode": barcode[:191] if barcode else None,
        "unit_type": map_unit_type(row.get("unit_type")),
        "item_type_key_hint": map_item_type_key(row.get("item_type")),
        "recommended_selling_price": selling_price,
        "recommended_buying_price": buying_price,
        "default_min_stock_level": row.get("min_stock_level") or None,
        "image_url": normalize_global_image_url(row.get("image_url")),
        "sku_template": (row.get("product_code") or "").strip() or None,
        "sort_name": name.lower(),
    }


def load_products(
    categories: dict[str, dict[str, str]],
    sell_prices: dict[str, str],
    buy_prices: dict[str, str],
) -> list[dict]:
    products: list[dict] = []
    with (IMPORTS / "items.csv").open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            product = parse_product_row(row, categories, sell_prices, buy_prices)
            if product:
                products.append(product)
    products.sort(key=lambda p: (p["category_id"] or "", p["sort_name"]))
    return products


def unique_slugs(categories: dict[str, dict[str, str]]) -> dict[str, str]:
    used: set[str] = set()
    slug_by_id: dict[str, str] = {}
    ordered = sorted(
        categories.values(),
        key=lambda c: (int(c.get("position") or 0), c["name"].lower()),
    )
    for index, category in enumerate(ordered):
        base = slugify(category["name"])
        slug = base
        suffix = 2
        while slug in used:
            slug = f"{base}-{suffix}"[:191]
            suffix += 1
        used.add(slug)
        slug_by_id[category["id"]] = slug
    return slug_by_id


def chunk(values: list[str], size: int) -> list[list[str]]:
    return [values[i : i + size] for i in range(0, len(values), size)]


def build_pack_membership(
    products: list[dict],
    categories: dict[str, dict[str, str]],
) -> dict[str, list[str]]:
    cat_name_by_id = {cid: cat["name"] for cid, cat in categories.items()}

    beverage_ids: list[str] = []
    grocery_ids: list[str] = []
    mini_mart_ids: list[str] = []

    for product in products:
        cat_name = cat_name_by_id.get(product["category_id"] or "", "")
        pid = product["id"]
        if BEVERAGE_CAT_RE.search(cat_name):
            beverage_ids.append(pid)
        if GROCERY_CAT_RE.search(cat_name):
            grocery_ids.append(pid)
        if product["barcode"] and product["recommended_selling_price"]:
            mini_mart_ids.append(pid)

    return {
        PACK_MINI_MART: mini_mart_ids[:120],
        PACK_BEVERAGES: beverage_ids[:200],
        PACK_GROCERY: grocery_ids[:200],
    }


def emit_reseed_preamble(out) -> None:
    out.write("-- Replace prior global catalog seed (V122 hand-written data).\n")
    out.write(f"SET @catalog_id = '{CATALOG_ID}';\n\n")
    out.write(
        "UPDATE items i\n"
        "INNER JOIN global_products gp ON gp.id = i.global_product_source_id\n"
        "   SET i.global_product_source_id = NULL\n"
        " WHERE gp.catalog_id = @catalog_id;\n\n"
    )
    out.write(
        "DELETE gppi FROM global_product_pack_items gppi\n"
        "INNER JOIN global_product_packs gpp ON gpp.id = gppi.pack_id\n"
        " WHERE gpp.catalog_id = @catalog_id;\n\n"
    )
    out.write("DELETE FROM global_product_packs WHERE catalog_id = @catalog_id;\n\n")
    out.write(
        "DELETE gpsl FROM global_product_supplier_links gpsl\n"
        "INNER JOIN global_products gp ON gp.id = gpsl.global_product_id\n"
        " WHERE gp.catalog_id = @catalog_id;\n\n"
    )
    out.write("DELETE FROM global_products WHERE catalog_id = @catalog_id;\n\n")
    out.write("DELETE FROM global_categories WHERE catalog_id = @catalog_id;\n\n")


def emit_sql(
    categories: dict[str, dict[str, str]],
    products: list[dict],
    slug_by_id: dict[str, str],
    pack_members: dict[str, list[str]],
    *,
    reseed: bool = False,
) -> None:
    out = sys.stdout
    out.write("-- Global catalog seed generated from legacy import CSVs.\n")
    out.write("-- Regenerate: python3 backend/scripts/generate_global_catalog_seed.py [--reseed]\n")
    out.write(f"-- Source business: {SOURCE_BUSINESS_ID}\n")
    out.write("-- Prices are hints in KES and should be reviewed by the shop owner on adopt.\n\n")

    if reseed:
        emit_reseed_preamble(out)
    else:
        out.write(f"SET @catalog_id = '{CATALOG_ID}';\n\n")

    out.write(
        "INSERT INTO global_catalogs (id, code, name, region_code, currency, status, version)\n"
        "VALUES (@catalog_id, 'default', 'Kenya Retail Catalog', 'KE', 'KES', 'published', 2)\n"
        "ON DUPLICATE KEY UPDATE\n"
        "  name = VALUES(name),\n"
        "  region_code = VALUES(region_code),\n"
        "  currency = VALUES(currency),\n"
        "  status = VALUES(status),\n"
        "  version = VALUES(version);\n\n"
    )

    out.write("-- Categories\n")
    category_rows: list[str] = []
    ordered_categories = sorted(
        categories.values(),
        key=lambda c: (int(c.get("position") or 0), c["name"].lower()),
    )
    for index, category in enumerate(ordered_categories):
        slug = slug_by_id[category["id"]]
        category_rows.append(
            "("
            f"{sql_str(category['id'])}, @catalog_id, "
            f"{sql_str(category['name'][:255])}, {sql_str(slug)}, "
            f"{int(category.get('position') or index)}, {sql_str(slug)}, TRUE)"
        )
    for batch in chunk(category_rows, 40):
        out.write(
            "INSERT INTO global_categories "
            "(id, catalog_id, name, slug, position, tenant_category_slug_hint, active) VALUES\n"
        )
        out.write(",\n".join(batch))
        out.write(";\n\n")

    out.write("-- Products\n")
    product_rows: list[str] = []
    for index, product in enumerate(products):
        product_rows.append(
            "("
            f"{sql_str(product['id'])}, @catalog_id, {sql_str(product['category_id'])}, "
            f"{sql_str(product['sku_template'])}, {sql_str(product['name'])}, "
            f"{sql_str(product['brand'])}, {sql_str(product['size'])}, "
            f"{sql_str(product['barcode'])}, {sql_str(product['unit_type'])}, "
            f"{sql_dec(product['recommended_buying_price'])}, "
            f"{sql_dec(product['recommended_selling_price'])}, "
            f"{sql_dec4(product['default_min_stock_level'])}, "
            f"{sql_str(product['image_url'])}, "
            f"{sql_str(product['item_type_key_hint'])}, 'published', {index})"
        )
    for batch in chunk(product_rows, 50):
        out.write(
            "INSERT INTO global_products "
            "(id, catalog_id, global_category_id, sku_template, name, brand, size, barcode, "
            "unit_type, recommended_buying_price, recommended_selling_price, "
            "default_min_stock_level, image_url, item_type_key_hint, status, sort_order) VALUES\n"
        )
        out.write(",\n".join(batch))
        out.write(";\n\n")

    out.write("-- Starter packs\n")
    out.write(
        "INSERT INTO global_product_packs (id, catalog_id, code, name, description, status, sort_order) VALUES\n"
        f"('{PACK_MINI_MART}', @catalog_id, 'mini-mart-starter', 'Mini Mart Starter', "
        "'Fast-moving barcoded products with suggested prices.', 'published', 0),\n"
        f"('{PACK_BEVERAGES}', @catalog_id, 'beverages-pack', 'Beverages Pack', "
        "'Drinks, juices, water, and dairy.', 'published', 1),\n"
        f"('{PACK_GROCERY}', @catalog_id, 'grocery-basics', 'Grocery Basics', "
        "'Flour, sugar, rice, oil, and staples.', 'published', 2);\n\n"
    )

    out.write("-- Pack items\n")
    for pack_id, product_ids in pack_members.items():
        if not product_ids:
            continue
        rows = [
            f"({sql_str(pack_id)}, {sql_str(product_id)}, {sort_order})"
            for sort_order, product_id in enumerate(product_ids)
        ]
        for batch in chunk(rows, 80):
            out.write(
                "INSERT INTO global_product_pack_items (pack_id, global_product_id, sort_order) VALUES\n"
            )
            out.write(",\n".join(batch))
            out.write(";\n\n")


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate global catalog Flyway seed SQL")
    parser.add_argument(
        "--reseed",
        action="store_true",
        help="Emit DELETE preamble for replacing an already-applied V122 seed",
    )
    args = parser.parse_args()

    categories = load_categories()
    sell_prices = load_latest_prices("selling_prices.csv")
    buy_prices = load_latest_prices("buying_prices.csv")
    products = load_products(categories, sell_prices, buy_prices)
    slug_by_id = unique_slugs(categories)
    pack_members = build_pack_membership(products, categories)
    emit_sql(categories, products, slug_by_id, pack_members, reseed=args.reseed)


if __name__ == "__main__":
    main()
