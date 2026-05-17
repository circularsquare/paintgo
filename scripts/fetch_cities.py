"""Fetch city admin polygons from Nominatim into app/src/main/assets/regions/cities.geojson.

Run from repo root: python scripts/fetch_cities.py
Re-run to refresh; safe to edit the CITIES list and re-run for additions.
Honors Nominatim's 1 req/sec fair-use limit. Requires Python 3.7+.
"""

import json
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

from shapely.geometry import mapping, shape

# Douglas-Peucker tolerance in degrees. 0.0001° ≈ 11m at the equator — well below what
# you can see at city-scale zoom (10–15), so visually lossless while cutting vertex
# count 50–80% on typical city polygons. Bump if files are still too big.
SIMPLIFY_TOLERANCE = 0.0001
# Decimal places to retain on each coordinate. 5 ≈ 1.1m precision, plenty for borders.
COORD_PRECISION = 5

# (display_name, country, query_override). query_override is what Nominatim sees when
# the polished display name doesn't match OSM's preferred label (e.g. "Mumbai" vs.
# OSM's "Greater Mumbai" relation).
CITIES = [
    ("San Francisco", "United States", None),
    ("Los Angeles", "United States", None),
    ("Chicago", "United States", None),
    ("Boston", "United States", None),
    ("Seattle", "United States", None),
    ("Miami", "United States", None),
    ("Austin", "United States", None),
    ("Washington", "United States", None),
    ("Philadelphia", "United States", None),
    ("Houston", "United States", None),
    ("Phoenix", "United States", None),
    ("San Diego", "United States", None),
    ("Dallas", "United States", None),
    ("San Jose", "United States", None),
    ("Atlanta", "United States", None),
    ("Denver", "United States", None),
    ("Portland", "United States", "Portland, Oregon"),
    ("Las Vegas", "United States", None),
    ("Detroit", "United States", None),
    ("Minneapolis", "United States", None),
    ("New Orleans", "United States", None),
    ("Nashville", "United States", None),
    ("Pittsburgh", "United States", None),
    ("Baltimore", "United States", None),
    ("Salt Lake City", "United States", None),
    ("Honolulu", "United States", None),
    ("St. Louis", "United States", None),
    ("Charlotte", "United States", None),
    ("Toronto", "Canada", None),
    ("Vancouver", "Canada", None),
    ("Mexico City", "Mexico", None),
    ("São Paulo", "Brazil", None),
    ("Buenos Aires", "Argentina", None),
    ("London", "United Kingdom", None),
    ("Paris", "France", None),
    ("Berlin", "Germany", None),
    ("Madrid", "Spain", None),
    ("Amsterdam", "Netherlands", None),
    ("Rome", "Italy", None),
    ("Stockholm", "Sweden", None),
    ("Vienna", "Austria", None),
    ("Lisbon", "Portugal", None),
    ("Tokyo", "Japan", None),
    ("Seoul", "South Korea", None),
    ("Singapore", "Singapore", None),
    ("Shanghai", "China", None),
    ("Bangkok", "Thailand", None),
    ("Dubai", "United Arab Emirates", None),
    ("Istanbul", "Turkey", None),
    ("Sydney", "Australia", None),
    ("Melbourne", "Australia", None),
    ("Cape Town", "South Africa", "City of Cape Town"),
    ("Cairo", "Egypt", None),
]

USER_AGENT = "PaintGo/0.1 (https://github.com/anitachen/paintgo)"
OUT_PATH = Path("app/src/main/assets/regions/cities.geojson")


def fetch_city(name: str, country: str):
    params = {
        "q": f"{name}, {country}",
        "polygon_geojson": "1",
        "format": "json",
        "limit": "5",
    }
    url = "https://nominatim.openstreetmap.org/search?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=30) as resp:
        results = json.loads(resp.read())
    # Only accept admin boundary polygons. A previous lenient fallback silently
    # grabbed unrelated buildings (e.g. it returned an office block in Hyderabad
    # for a Mumbai query) — refuse and let the caller see "no result" instead.
    for r in results:
        if (
            r.get("class") == "boundary"
            and r.get("type") == "administrative"
            and r.get("geojson")
            and r["geojson"].get("type") in ("Polygon", "MultiPolygon")
        ):
            return r
    return None


def _round_coords(coords, n):
    if coords and isinstance(coords[0], (int, float)):
        return [round(c, n) for c in coords]
    return [_round_coords(c, n) for c in coords]


def compress_feature(f: dict) -> dict:
    geom = shape(f["geometry"]).simplify(SIMPLIFY_TOLERANCE, preserve_topology=True)
    g = mapping(geom)
    f["geometry"] = {"type": g["type"], "coordinates": _round_coords(g["coordinates"], COORD_PRECISION)}
    f["properties"].pop("display_name", None)
    return f


def main() -> int:
    features = []
    for name, country, query_override in CITIES:
        query_name = query_override or name
        print(f"Fetching {name}, {country}...", flush=True)
        try:
            r = fetch_city(query_name, country)
        except Exception as e:
            print(f"  ERROR: {e}", flush=True)
            time.sleep(1)
            continue
        if r is None:
            print("  no polygon result", flush=True)
        else:
            features.append({
                "type": "Feature",
                "properties": {
                    "name": name,
                    "country": country,
                    "osm_id": r.get("osm_id"),
                    "osm_type": r.get("osm_type"),
                    "display_name": r.get("display_name"),
                },
                "geometry": r["geojson"],
            })
            print(f"  OK ({r['geojson'].get('type')})", flush=True)
        time.sleep(1)

    raw_kb = sum(len(json.dumps(f)) for f in features) / 1024
    features = [compress_feature(f) for f in features]
    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUT_PATH.write_text(
        json.dumps({"type": "FeatureCollection", "features": features}),
        encoding="utf-8",
    )
    size_kb = OUT_PATH.stat().st_size / 1024
    print(
        f"\nWrote {len(features)}/{len(CITIES)} features to {OUT_PATH} "
        f"({size_kb:.0f} KB, down from {raw_kb:.0f} KB raw)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
