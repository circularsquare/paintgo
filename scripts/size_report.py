import json
import sys

p = "app/src/main/assets/regions/cities.geojson"
d = json.load(open(p, encoding="utf-8"))
rows = []
for f in d["features"]:
    name = f["properties"]["name"]
    sz = len(json.dumps(f))
    rows.append((sz, name))
rows.sort(reverse=True)
total = sum(r[0] for r in rows)
print(f"{'City':<25} {'KB':>8}")
print("-" * 35)
for sz, name in rows:
    print(f"{name:<25} {sz/1024:>8.1f}")
print("-" * 35)
print(f"{'TOTAL':<25} {total/1024:>8.1f}")
