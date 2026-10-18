#!/usr/bin/env python3
"""Generate sql/092_zone_pricing_seed.sql from the 2026-10-18 Stripe inventory.

Input: docs/billing-regional-pricing/stripe-price-inventory-20261018.json
(the read-only dump of the test account's band-priced catalog).

Output: 17 rate-band zones x 11 SKUs = 187 price bindings with real stripe
Price ids, 114 band-country routes (local currency, USD where the band price
lacks the local currency), every remaining ISO country routed to the 1.00
band in USD, and legacy (pre-band) Price ids registered as aliases.

All switches ship OFF; sql/093_enable_zone_pricing.sql (also generated here)
is the step that turns zones/countries on after preflight passes.

Usage: python3 scripts/gen_zone_pricing_seed.py
"""

import argparse
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
INVENTORY = REPO / "docs/billing-regional-pricing" / "stripe-price-inventory-20261018.json"
DEFAULT_OUT = REPO / "sql" / "092_zone_pricing_seed.sql"
DEFAULT_ENABLE_OUT = REPO / "sql" / "093_enable_zone_pricing.sql"

VERSION = "regional-20261018-v1"

# factor -> zone code (the band matrix the Stripe account actually sells)
BAND_ZONES = {
    0.20: "Z020", 0.25: "Z025", 0.30: "Z030", 0.35: "Z035", 0.40: "Z040",
    0.45: "Z045", 0.50: "Z050", 0.55: "Z055", 0.60: "Z060", 0.65: "Z065",
    0.70: "Z070", 0.75: "Z075", 0.80: "Z080", 0.85: "Z085", 0.90: "Z090",
    0.95: "Z095", 1.00: "Z100",
}
DEFAULT_ZONE = "Z100"

# sku -> (product name, interval or None for one_time, sku_kind, renewal sku)
SKU_SPECS = {
    "basic_monthly": ("Verla Basic", "month", "subscription_plan", None),
    "basic_yearly": ("Verla Basic", "year", "subscription_plan", None),
    "plus_monthly": ("Verla Plus", "month", "subscription_plan", None),
    "plus_yearly": ("Verla Plus", "year", "subscription_plan", None),
    "pro_monthly": ("Verla Pro", "month", "subscription_plan", None),
    "pro_yearly": ("Verla Pro", "year", "subscription_plan", None),
    "pro_trial_to_monthly": ("Pro Trial", "week", "subscription_plan", "pro_monthly"),
    "addon_assignment_3": ("Extra Assignment Pack", None, "addon", None),
    "addon_detection_5": ("Extra AI Detection Pack", None, "addon", None),
    "addon_humanizer_3": ("Extra Humanizer Pack", None, "addon", None),
    "study_pass_subscription_30d": ("Study Pass", "month", "study_pass", None),
}

# Band country lists (Chinese, exactly as stored in the Stripe Price nicknames).
BAND_COUNTRIES = {
    0.20: [("印度", "IN"), ("巴基斯坦", "PK"), ("埃及", "EG"), ("尼日利亚", "NG"), ("孟加拉国", "BD"),
           ("埃塞俄比亚", "ET"), ("马拉维", "MW"), ("津巴布韦", "ZW"), ("巴勒斯坦", "PS"), ("卢旺达", "RW"),
           ("坦桑尼亚", "TZ"), ("乌干达", "UG"), ("委内瑞拉", "VE"), ("赞比亚", "ZM"), ("阿富汗", "AF"),
           ("喀麦隆", "CM"), ("老挝", "LA"), ("利比里亚", "LR"), ("缅甸", "MM"), ("尼泊尔", "NP"),
           ("乌兹别克斯坦", "UZ")],
    0.25: [("越南", "VN"), ("印度尼西亚", "ID"), ("肯尼亚", "KE"), ("突尼斯", "TN"), ("加纳", "GH"),
           ("乌克兰", "UA"), ("玻利维亚", "BO"), ("斯里兰卡", "LK"), ("柬埔寨", "KH"), ("科特迪瓦", "CI")],
    0.30: [("菲律宾", "PH"), ("阿尔及利亚", "DZ"), ("摩洛哥", "MA"), ("纳米比亚", "NA"), ("阿塞拜疆", "AZ"),
           ("泰国", "TH"), ("洪都拉斯", "HN"), ("蒙古", "MN")],
    0.35: [("马来西亚", "MY"), ("哥伦比亚", "CO"), ("南非", "ZA"), ("厄瓜多尔", "EC"), ("博茨瓦纳", "BW"),
           ("危地马拉", "GT"), ("伊拉克", "IQ"), ("约旦", "JO"), ("萨尔瓦多", "SV"), ("加蓬", "GA"),
           ("格鲁吉亚", "GE"), ("黎巴嫩", "LB"), ("摩尔多瓦", "MD")],
    0.40: [("巴西", "BR"), ("阿根廷", "AR"), ("土耳其", "TR"), ("牙买加", "JM"), ("秘鲁", "PE"),
           ("伯利兹", "BZ"), ("阿尔巴尼亚", "AL"), ("哈萨克斯坦", "KZ"), ("黑山", "ME"),
           ("巴布亚新几内亚", "PG"), ("塞尔维亚", "RS")],
    0.45: [("墨西哥", "MX"), ("中国大陆", "CN"), ("罗马尼亚", "RO"), ("圣卢西亚", "LC"), ("智利", "CL"),
           ("马尔代夫", "MV")],
    0.50: [("巴拿马", "PA"), ("哥斯达黎加", "CR"), ("克罗地亚", "HR"), ("匈牙利", "HU"), ("阿曼", "OM"),
           ("波兰", "PL"), ("塞舌尔", "SC"), ("特立尼达和多巴哥", "TT")],
    0.55: [("圭亚那", "GY"), ("希腊", "GR"), ("巴巴多斯", "BB"), ("文莱", "BN"), ("拉脱维亚", "LV"),
           ("波多黎各", "PR"), ("斯洛伐克", "SK")],
    0.60: [("斯洛文尼亚", "SI"), ("沙特阿拉伯", "SA"), ("乌拉圭", "UY")],
    0.65: [("中国台湾", "TW"), ("韩国", "KR"), ("日本", "JP"), ("西班牙", "ES"), ("科威特", "KW")],
    0.70: [("意大利", "IT")],
    0.75: [("阿联酋", "AE"), ("法国", "FR")],
    0.80: [("中国香港", "HK"), ("德国", "DE"), ("中国澳门", "MO"), ("奥地利", "AT"), ("比利时", "BE"),
           ("芬兰", "FI")],
    0.85: [("英国", "GB"), ("新西兰", "NZ"), ("荷兰", "NL"), ("巴哈马", "BS"), ("瑞典", "SE")],
    0.90: [("加拿大", "CA"), ("新加坡", "SG")],
    0.95: [("澳大利亚", "AU"), ("卢森堡", "LU")],
    1.00: [("美国", "US"), ("爱尔兰", "IE"), ("百慕大", "BM"), ("挪威", "NO")],
}

# Country billing currency; USD where the band Price lacks the local currency
# (TND, GHS, IQD, JOD, OMR, KWD, VES are absent from the Stripe currency options).
COUNTRY_CURRENCY = {
    "IN": "inr", "PK": "pkr", "EG": "egp", "NG": "ngn", "BD": "bdt", "ET": "etb",
    "MW": "mwk", "ZW": "usd", "PS": "ils", "RW": "rwf", "TZ": "tzs", "UG": "ugx",
    "VE": "usd", "ZM": "zmw", "AF": "afn", "CM": "xaf", "LA": "lak", "LR": "lrd",
    "MM": "mmk", "NP": "npr", "UZ": "uzs",
    "VN": "vnd", "ID": "idr", "KE": "kes", "TN": "usd", "GH": "usd", "UA": "uah",
    "BO": "bob", "LK": "lkr", "KH": "khr", "CI": "xof",
    "PH": "php", "DZ": "dzd", "MA": "mad", "NA": "nad", "AZ": "azn", "TH": "thb",
    "HN": "hnl", "MN": "mnt",
    "MY": "myr", "CO": "cop", "ZA": "zar", "EC": "usd", "BW": "bwp", "GT": "gtq",
    "IQ": "usd", "JO": "usd", "SV": "usd", "GA": "xaf", "GE": "gel", "LB": "lbp",
    "MD": "mdl",
    "BR": "brl", "AR": "ars", "TR": "try", "JM": "jmd", "PE": "pen", "BZ": "bzd",
    "AL": "all", "KZ": "kzt", "ME": "eur", "PG": "pgk", "RS": "rsd",
    "MX": "mxn", "CN": "cny", "RO": "ron", "LC": "xcd", "CL": "clp", "MV": "mvr",
    "PA": "usd", "CR": "crc", "HR": "eur", "HU": "huf", "OM": "usd", "PL": "pln",
    "SC": "scr", "TT": "ttd",
    "GY": "gyd", "GR": "eur", "BB": "bbd", "BN": "bnd", "LV": "eur", "PR": "usd",
    "SK": "eur",
    "SI": "eur", "SA": "sar", "UY": "uyu",
    "TW": "twd", "KR": "krw", "JP": "jpy", "ES": "eur", "KW": "usd",
    "IT": "eur",
    "AE": "aed", "FR": "eur",
    "HK": "hkd", "DE": "eur", "MO": "mop", "AT": "eur", "BE": "eur", "FI": "eur",
    "GB": "gbp", "NZ": "nzd", "NL": "eur", "BS": "bsd", "SE": "sek",
    "CA": "cad", "SG": "sgd",
    "AU": "aud", "LU": "eur",
    "US": "usd", "IE": "eur", "BM": "bmd", "NO": "nok",
}

ISO_COUNTRIES = (
    "AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT "
    "BV BW BY BZ CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH "
    "ER ES ET FI FJ FK FM FO FR GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT "
    "HU ID IE IL IM IN IO IQ IR IS IT JE JM JO JP KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS "
    "LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT MU MV MW MX MY MZ NA NC NE NF NG NI "
    "NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW SA SB SC SD SE SG SH "
    "SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG UM US "
    "UY UZ VA VC VE VG VI VN VU WF WS YE YT ZA ZM ZW"
).split()

BAND_NICK = re.compile(r"^([\d.]+)倍率区（(.+)）$")


def sql_str(value) -> str:
    return "'" + str(value).replace("\\", "\\\\").replace("'", "''") + "'"


def load_products() -> dict:
    inventory = json.loads(INVENTORY.read_text(encoding="utf-8"))
    by_name = {}
    for product_id, product in inventory["products"].items():
        by_name[product["name"]] = (product_id, product["prices"])
    return by_name


def band_prices(products: dict) -> dict:
    """factor -> sku -> price dict; asserts every band has all 11 SKUs."""
    result = {}
    for sku, (product_name, interval, _kind, _renewal) in SKU_SPECS.items():
        product_id, prices = products[product_name]
        for price in prices:
            if not price["active"]:
                continue
            match = BAND_NICK.match(price.get("nickname") or "")
            if not match:
                continue
            factor = round(float(match.group(1)), 2)
            if price.get("interval") != interval:
                continue
            result.setdefault(factor, {}).setdefault(sku, dict(price, product_id=product_id))
    assert set(result) == set(BAND_ZONES), f"bands mismatch: {sorted(result)}"
    for factor, skus in result.items():
        assert set(skus) == set(SKU_SPECS), f"band {factor} missing {set(SKU_SPECS) - set(skus)}"
    return result


def legacy_aliases(products: dict) -> list:
    """Pre-band active prices: they keep serving existing contracts, never new sales."""
    aliases_by_product = {
        "Verla Basic": {"month": "basic_monthly", "year": "basic_yearly"},
        "Verla Plus": {"month": "plus_monthly", "year": "plus_yearly"},
        "Verla Pro": {"month": "pro_monthly", "year": "pro_yearly"},
        "Pro Trial": {"week": "pro_trial_to_monthly"},
        "Study Pass": {"month": "study_pass_subscription_30d", "day": "study_pass_subscription_30d"},
        "Extra Assignment Pack": {None: "addon_assignment_3"},
        "Extra AI Detection Pack": {None: "addon_detection_5"},
        "Extra Humanizer Pack": {None: "addon_humanizer_3"},
    }
    rows = []
    for product_name, by_interval in aliases_by_product.items():
        _pid, prices = products[product_name]
        for price in prices:
            if not price["active"] or BAND_NICK.match(price.get("nickname") or ""):
                continue
            sku = by_interval.get(price.get("interval"))
            if sku is None:
                continue
            rows.append((price["id"], sku, SKU_SPECS[sku][2]))
    return rows


def main() -> int:
    products = load_products()
    bands = band_prices(products)
    aliases = legacy_aliases(products)

    # Currency sets must be identical inside a band so one route currency works for every SKU.
    band_currencies = {}
    for factor, skus in bands.items():
        currency_sets = []
        for price in skus.values():
            options = dict(price["currency_options"])
            options.setdefault(price["currency"], price["unit_amount"])
            price["amounts"] = options
            currency_sets.append(set(options))
        common = set.intersection(*currency_sets)
        assert "usd" in common, f"band {factor} lacks usd"
        band_currencies[factor] = common

    zone_rows, price_blocks = [], []
    total_bindings = total_amounts = 0
    for factor in sorted(BAND_ZONES):
        zone_code = BAND_ZONES[factor]
        countries = BAND_COUNTRIES[factor]
        label = f"{factor:.2f}倍率区 · " + "/".join(name for name, _iso in countries)
        if zone_code == DEFAULT_ZONE:
            label += "（其余国家默认档）"
        assert len(label) <= 255, label
        zone_rows.append(
            f"({sql_str(zone_code)}, {sql_str(label)}, {sql_str(f'{factor:.2f}')}, {sql_str(VERSION)}, 0)"
        )
        rows = []
        for sku in sorted(SKU_SPECS):
            _product, interval, kind, renewal = SKU_SPECS[sku]
            price = bands[factor][sku]
            amounts = {cur: {"amountMinor": amount} for cur, amount in sorted(price["amounts"].items())}
            total_bindings += 1
            total_amounts += len(amounts)
            price_type = "one_time" if interval is None else "recurring"
            rows.append(
                f"({sql_str(zone_code)}, {sql_str(VERSION)}, {sql_str(sku)}, {sql_str(kind)}, "
                f"{sql_str(price['id'])}, {sql_str(price['product_id'])}, {sql_str(price_type)}, "
                f"{sql_str(interval) if interval else 'NULL'}, "
                f"{price.get('interval_count') or 1}, {price['unit_amount']}, "
                f"{sql_str(json.dumps(amounts, separators=(',', ':'), sort_keys=True))}, "
                f"{sql_str(renewal) if renewal else 'NULL'}, 1)"
            )
        price_blocks.append((zone_code, rows))

    # Routes: 114 band countries with their local currency (USD when missing),
    # every remaining ISO country to the 1.00 band in USD.
    mapped = {iso for countries in BAND_COUNTRIES.values() for _name, iso in countries}
    assert len(mapped) == 114, len(mapped)
    route_rows, fallback_notes = [], []
    for factor in sorted(BAND_ZONES):
        for name, iso in BAND_COUNTRIES[factor]:
            currency = COUNTRY_CURRENCY[iso]
            if currency not in band_currencies[factor]:
                fallback_notes.append(f"{iso}:{currency}->usd")
                currency = "usd"
            route_rows.append(
                f"({sql_str(iso)}, {sql_str(BAND_ZONES[factor])}, {sql_str(currency)}, {sql_str(name)}, 0)"
            )
    rest = [iso for iso in ISO_COUNTRIES if iso not in mapped]
    for iso in rest:
        route_rows.append(f"({sql_str(iso)}, {sql_str(DEFAULT_ZONE)}, 'usd', {sql_str(iso)}, 0)")

    assert len(route_rows) == len(ISO_COUNTRIES), (len(route_rows), len(ISO_COUNTRIES))
    assert len(aliases) == 15, len(aliases)

    alias_rows = [
        f"({sql_str(pid)}, {sql_str(sku)}, {sql_str(kind)}, {sql_str('2026-10-18 盘点：批次前旧价，仅存量合同')})"
        for pid, sku, kind in sorted(aliases)
    ]

    lines = [
        "-- GENERATED by scripts/gen_zone_pricing_seed.py — do not edit by hand.",
        "-- Source: docs/billing-regional-pricing/stripe-price-inventory-20261018.json",
        f"-- Counts asserted: {len(BAND_ZONES)} zones / {total_bindings} price bindings / "
        f"{total_amounts} local-currency amounts / {len(ISO_COUNTRIES)} country routes "
        f"({len(mapped)} band countries + {len(rest)} default 1.00) / {len(aliases)} legacy aliases.",
        "-- Idempotent via ON DUPLICATE KEY UPDATE; stripe_price_id is only backfilled when NULL.",
        "-- All switches ship OFF; run 093_enable_zone_pricing.sql after preflight is green.",
        "",
        "USE studyagent;",
        "",
        "-- Zones (is_active=0 until preflight passes)",
        "INSERT INTO pricing_zones (zone_code, label, reference_factor, active_version, is_active) VALUES",
        ",\n".join("    " + row for row in zone_rows),
        "ON DUPLICATE KEY UPDATE label=VALUES(label), reference_factor=VALUES(reference_factor), "
        "active_version=VALUES(active_version);",
        "",
        "-- Country routes (is_enabled=0 until preflight passes)",
        "INSERT INTO pricing_country_routes (country_code, zone_code, currency, label, is_enabled) VALUES",
        ",\n".join("    " + row for row in route_rows),
        "ON DUPLICATE KEY UPDATE zone_code=VALUES(zone_code), currency=VALUES(currency), label=VALUES(label);",
        "",
        "-- Immutable Price registry (real Stripe Price ids from the 2026-10-18 inventory)",
    ]
    for zone_code, rows in price_blocks:
        lines += [
            f"-- {zone_code} ({VERSION})",
            "INSERT INTO pricing_zone_prices (zone_code, price_version, sku_code, sku_kind, "
            "stripe_price_id, stripe_product_id, price_type, billing_interval, interval_count, "
            "usd_default_amount_minor, currency_amounts, renewal_sku_code, is_active) VALUES",
            ",\n".join("    " + row for row in rows),
            "ON DUPLICATE KEY UPDATE currency_amounts=VALUES(currency_amounts), "
            "usd_default_amount_minor=VALUES(usd_default_amount_minor), "
            "renewal_sku_code=VALUES(renewal_sku_code), "
            "stripe_price_id=COALESCE(stripe_price_id, VALUES(stripe_price_id)), "
            "stripe_product_id=COALESCE(stripe_product_id, VALUES(stripe_product_id));",
            "",
        ]
    lines += [
        "-- Legacy (pre-band) Price aliases: resolvable forever, never used for new sales.",
        "INSERT INTO stripe_price_aliases (stripe_price_id, sku_code, sku_kind, note) VALUES",
        ",\n".join("    " + row for row in alias_rows),
        "ON DUPLICATE KEY UPDATE sku_code=VALUES(sku_code), sku_kind=VALUES(sku_kind), note=VALUES(note);",
        "",
    ]

    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=str(DEFAULT_OUT))
    parser.add_argument("--enable-out", default=str(DEFAULT_ENABLE_OUT))
    args = parser.parse_args()
    Path(args.out).write_text("\n".join(lines), encoding="utf-8")

    enable_lines = [
        "-- GENERATED by scripts/gen_zone_pricing_seed.py — run ONLY after:",
        "--   1) 092 seed applied, 2) zone pricing preflight green, 3) storefront smoke passed.",
        "-- Flip every zone and country route on (new purchases use the band matrix).",
        "USE studyagent;",
        "UPDATE pricing_zones SET is_active=1 WHERE active_version='" + VERSION + "';",
        "UPDATE pricing_country_routes SET is_enabled=1;",
        "",
        "-- Rollback: UPDATE pricing_zones SET is_active=0; UPDATE pricing_country_routes SET is_enabled=0;",
        "",
    ]
    Path(args.enable_out).write_text("\n".join(enable_lines), encoding="utf-8")

    print(f"zones={len(BAND_ZONES)} bindings={total_bindings} amounts={total_amounts} "
          f"routes={len(route_rows)} aliases={len(aliases)}")
    if fallback_notes:
        print("currency fallbacks:", ", ".join(fallback_notes))
    print(f"written: {args.out}")
    print(f"written: {args.enable_out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
