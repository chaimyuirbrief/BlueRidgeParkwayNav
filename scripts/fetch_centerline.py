#!/usr/bin/env python3
"""Fetch the real Blue Ridge Parkway road geometry from OpenStreetMap (Overpass API)
and embed it as the high-resolution centerline in app/src/main/assets/brp_data.json.

Runs in CI (GitHub Actions has open internet). The app then draws and routes the
Parkway from exact road geometry offline, with no runtime road-snapping needed.

Data © OpenStreetMap contributors (ODbL) — attributed in the app's About screen.
"""
import json
import math
import sys
import time
import urllib.parse
import urllib.request

OVERPASS_ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]

QUERY = """[out:json][timeout:300];
relation["route"="road"]["name"="Blue Ridge Parkway"];
out geom;
"""

NORTH_TERMINUS = (38.0331, -78.8590)  # Rockfish Gap, MP 0
OFFICIAL_LENGTH_MILES = 469.1
DATA_FILE = "app/src/main/assets/brp_data.json"


def haversine_m(a, b):
    lat1, lon1 = a
    lat2, lon2 = b
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def fetch_overpass():
    payload = urllib.parse.urlencode({"data": QUERY}).encode()
    last_err = None
    for url in OVERPASS_ENDPOINTS:
        for attempt in range(3):
            try:
                req = urllib.request.Request(
                    url, data=payload,
                    headers={"User-Agent": "BlueRidgeParkwayNav-CI/1.0"},
                )
                with urllib.request.urlopen(req, timeout=300) as resp:
                    return json.load(resp)
            except Exception as e:  # noqa: BLE001
                last_err = e
                print(f"Overpass attempt failed ({url}): {e}", file=sys.stderr)
                time.sleep(15)
    sys.exit(f"All Overpass endpoints failed: {last_err}")


def pick_relation(data):
    rels = [e for e in data.get("elements", []) if e.get("type") == "relation"]
    if not rels:
        sys.exit("No 'Blue Ridge Parkway' route relation found")
    # If several relations match, take the one with the most way members.
    rel = max(rels, key=lambda r: len(r.get("members", [])))
    print(f"Using relation {rel['id']} with {len(rel.get('members', []))} members")
    return rel


def way_segments(rel):
    segs = []
    for m in rel.get("members", []):
        if m.get("type") != "way" or not m.get("geometry"):
            continue
        role = (m.get("role") or "").lower()
        if "link" in role:  # skip ramps/spurs
            continue
        pts = [(p["lat"], p["lon"]) for p in m["geometry"]]
        if len(pts) >= 2:
            segs.append(pts)
    if not segs:
        sys.exit("Relation has no way geometry")
    return segs


def stitch(segs):
    """Greedy stitch: start at the segment endpoint nearest the northern terminus,
    then repeatedly append the unused segment whose nearer endpoint is closest to
    the current end (reversing as needed). Robust to unordered relation members."""
    remaining = list(segs)

    def nearest_start(seg):
        return min(haversine_m(NORTH_TERMINUS, seg[0]), haversine_m(NORTH_TERMINUS, seg[-1]))

    first = min(remaining, key=nearest_start)
    remaining.remove(first)
    if haversine_m(NORTH_TERMINUS, first[-1]) < haversine_m(NORTH_TERMINUS, first[0]):
        first = first[::-1]
    line = list(first)

    while remaining:
        end = line[-1]
        best, best_d, best_rev = None, float("inf"), False
        for seg in remaining:
            d0 = haversine_m(end, seg[0])
            d1 = haversine_m(end, seg[-1])
            if d0 < best_d:
                best, best_d, best_rev = seg, d0, False
            if d1 < best_d:
                best, best_d, best_rev = seg, d1, True
        remaining.remove(best)
        pts = best[::-1] if best_rev else best
        if best_d > 5000:
            print(f"warning: {best_d/1000:.1f} km gap while stitching", file=sys.stderr)
        if haversine_m(line[-1], pts[0]) < 1.0:
            pts = pts[1:]
        line.extend(pts)
    return line


def simplify(pts, tol_m=30.0):
    out = [pts[0]]
    for p in pts[1:]:
        if haversine_m(out[-1], p) >= tol_m:
            out.append(p)
    if out[-1] != pts[-1]:
        out.append(pts[-1])
    return out


def main():
    data = fetch_overpass()
    rel = pick_relation(data)
    line = stitch(way_segments(rel))
    print(f"Stitched {len(line)} raw points")

    line = simplify(line, 30.0)
    total_m = sum(haversine_m(line[i], line[i + 1]) for i in range(len(line) - 1))
    total_mi = total_m / 1609.344
    print(f"Simplified to {len(line)} points, length {total_mi:.1f} mi")

    # Sanity: the Parkway is ~469 miles. Refuse to commit garbage.
    if not (430 <= total_mi <= 520):
        sys.exit(f"Length {total_mi:.1f} mi outside sanity range — aborting")

    # Cumulative distance scaled so the southern terminus lands on MP 469.1.
    scale = OFFICIAL_LENGTH_MILES / total_mi
    centerline = []
    acc = 0.0
    prev = None
    for p in line:
        if prev is not None:
            acc += haversine_m(prev, p) / 1609.344
        prev = p
        centerline.append({
            "mile": round(acc * scale, 2),
            "lat": round(p[0], 5),
            "lng": round(p[1], 5),
        })

    with open(DATA_FILE, encoding="utf-8") as f:
        brp = json.load(f)
    brp["centerline"] = centerline
    with open(DATA_FILE, "w", encoding="utf-8") as f:
        json.dump(brp, f, ensure_ascii=False, separators=(",", ":"))
    print(f"Wrote {len(centerline)} centerline points to {DATA_FILE}")


if __name__ == "__main__":
    main()
