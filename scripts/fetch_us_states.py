"""Replace app/src/main/assets/regions/us-states.geojson with a high-res version
derived from Natural Earth 10m admin_1 (states/provinces, global), filtered to USA
and simplified.

Run from repo root: python scripts/fetch_us_states.py
"""

import json
import urllib.request
from pathlib import Path

from shapely.geometry import mapping, shape
from shapely.ops import unary_union

NE_URL = (
    "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/"
    "master/geojson/ne_10m_admin_1_states_provinces.geojson"
)
OUT_PATH = Path("app/src/main/assets/regions/us-states.geojson")
COUNTRIES_PATH = Path("app/src/main/assets/regions/countries.geojson")
SIMPLIFY_TOLERANCE = 0.0001  # ~11m, same as cities
COORD_PRECISION = 5


def _round_coords(coords, n):
    if coords and isinstance(coords[0], (int, float)):
        return [round(c, n) for c in coords]
    return [_round_coords(c, n) for c in coords]


def main() -> int:
    print(f"Downloading {NE_URL}...", flush=True)
    with urllib.request.urlopen(NE_URL, timeout=120) as resp:
        ne = json.loads(resp.read())
    print(f"  got {len(ne['features'])} global admin_1 features", flush=True)

    # NE files Puerto Rico under its own admin rather than USA; include it explicitly
    # so we don't regress from the previous bundle's 52-feature coverage.
    us_admins = {"United States of America", "Puerto Rico"}
    us = [f for f in ne["features"] if f["properties"].get("admin") in us_admins]
    print(f"  {len(us)} match US (incl. Puerto Rico)", flush=True)

    raw_kb = sum(len(json.dumps(f)) for f in us) / 1024
    out_features = []
    for f in us:
        geom = shape(f["geometry"]).simplify(SIMPLIFY_TOLERANCE, preserve_topology=True)
        g = mapping(geom)
        out_features.append({
            "type": "Feature",
            "properties": {
                "name": f["properties"].get("name"),
                "iso_3166_2": f["properties"].get("iso_3166_2"),
            },
            "geometry": {
                "type": g["type"],
                "coordinates": _round_coords(g["coordinates"], COORD_PRECISION),
            },
        })

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUT_PATH.write_text(
        json.dumps({"type": "FeatureCollection", "features": out_features}),
        encoding="utf-8",
    )
    size_kb = OUT_PATH.stat().st_size / 1024
    print(
        f"\nWrote {len(out_features)} features to {OUT_PATH} "
        f"({size_kb:.0f} KB, down from {raw_kb:.0f} KB raw)"
    )

    # Replace USA geometry in countries.geojson with the union of these states, so
    # the country outline aligns exactly with the state outlines at any zoom.
    print(f"\nPatching USA in {COUNTRIES_PATH}...", flush=True)
    countries = json.loads(COUNTRIES_PATH.read_text(encoding="utf-8"))
    before_kb = len(json.dumps(countries)) / 1024
    state_shapes = [shape(f["geometry"]) for f in out_features]
    usa_union = unary_union(state_shapes)
    usa_geom = mapping(usa_union)
    usa_patched = {
        "type": usa_geom["type"],
        "coordinates": _round_coords(usa_geom["coordinates"], COORD_PRECISION),
    }
    patched_count = 0
    for f in countries["features"]:
        if f["properties"].get("ISO3166-1-Alpha-3") == "USA":
            f["geometry"] = usa_patched
            patched_count += 1
    if patched_count != 1:
        print(f"  WARN: expected to patch exactly 1 USA feature, patched {patched_count}")
    COUNTRIES_PATH.write_text(json.dumps(countries), encoding="utf-8")
    after_kb = len(json.dumps(countries)) / 1024
    print(f"  countries.geojson: {before_kb:.0f} KB -> {after_kb:.0f} KB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
