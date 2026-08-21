#!/usr/bin/env python3
"""Place every Blue Ridge Parkway POI/junction onto the real road geometry.

The POI coordinates were hand-estimated and sit up to ~10 miles off the actual
road. Their *mileposts*, however, are well-documented NPS values. Now that the
app embeds real OSM road geometry indexed by milepost, the reliable way to
position a feature is: official milepost -> calibrated geometry milepost ->
exact point on the road.

Calibration: cumulative-distance mileage drifts slightly from posted NPS
mileposts. We fit a monotonic piecewise-linear correction using "trusted
anchors" - features whose existing coordinate already lands within
TRUST_RADIUS_MI of the road (so both their coordinate and milepost agree).

Off-Parkway destinations (reached by a spur road, e.g. Mount Mitchell via
NC-128) are placed at their Parkway access point - the point a driver turns
off - and their description is annotated with the spur distance.

Usage: place_pois.py [verified.json]
  verified.json (optional) = [{"name","milepost","placement","spur_miles",...}]
"""
import json
import math
import sys

DATA_FILE = "app/src/main/assets/brp_data.json"
TRUST_RADIUS_MI = 0.30
MAX_ANCHOR_DRIFT_MI = 2.0   # reject anchors whose drift is an obvious outlier


def hav(lat1, lng1, lat2, lng2):
    r = 3958.7613
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lng2 - lng1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def nearest_point(centerline, lat, lng):
    best = min(centerline, key=lambda q: (q["lat"] - lat) ** 2 + (q["lng"] - lng) ** 2)
    return best, hav(lat, lng, best["lat"], best["lng"])


def build_calibration(centerline, features):
    """official milepost -> geometry milepost, from features already on the road."""
    anchors = []
    for f in features:
        best, dist = nearest_point(centerline, f["lat"], f["lng"])
        if dist <= TRUST_RADIUS_MI and abs(best["mile"] - f["mile"]) <= MAX_ANCHOR_DRIFT_MI:
            anchors.append((float(f["mile"]), float(best["mile"])))
    lo = min(c["mile"] for c in centerline)
    hi = max(c["mile"] for c in centerline)
    anchors.append((lo, lo))
    anchors.append((hi, hi))
    # average duplicates, sort, enforce monotonicity
    by_official = {}
    for o, g in anchors:
        by_official.setdefault(o, []).append(g)
    pts = sorted((o, sum(v) / len(v)) for o, v in by_official.items())
    mono = []
    for o, g in pts:
        if mono and g < mono[-1][1]:
            g = mono[-1][1]
        mono.append((o, g))
    return mono


def calibrate(mono, mp):
    if mp <= mono[0][0]:
        return mono[0][1]
    if mp >= mono[-1][0]:
        return mono[-1][1]
    for i in range(len(mono) - 1):
        o0, g0 = mono[i]
        o1, g1 = mono[i + 1]
        if o0 <= mp <= o1:
            t = 0.0 if o1 == o0 else (mp - o0) / (o1 - o0)
            return g0 + (g1 - g0) * t
    return mono[-1][1]


def point_at_mile(centerline, mile):
    """Interpolated position on the road at a geometry milepost."""
    if mile <= centerline[0]["mile"]:
        return centerline[0]["lat"], centerline[0]["lng"]
    if mile >= centerline[-1]["mile"]:
        return centerline[-1]["lat"], centerline[-1]["lng"]
    lo, hi = 0, len(centerline) - 1
    while lo < hi - 1:
        mid = (lo + hi) // 2
        if centerline[mid]["mile"] <= mile:
            lo = mid
        else:
            hi = mid
    a, b = centerline[lo], centerline[hi]
    span = b["mile"] - a["mile"]
    t = 0.0 if span <= 0 else (mile - a["mile"]) / span
    return a["lat"] + (b["lat"] - a["lat"]) * t, a["lng"] + (b["lng"] - a["lng"]) * t


def main():
    verified = {}
    if len(sys.argv) > 1:
        for v in json.load(open(sys.argv[1], encoding="utf-8")):
            verified[v["name"]] = v

    with open(DATA_FILE, encoding="utf-8") as fh:
        data = json.load(fh)
    centerline = sorted(data["centerline"], key=lambda p: p["mile"])
    features = data["pois"] + data["junctions"]

    mono = build_calibration(centerline, features)
    print(f"calibration anchors: {len(mono)}")

    # Features sharing a milepost (e.g. Linn Cove Viaduct + its visitor center) would land on
    # the exact same pixel and hide each other. Spread them a few hundred feet along the road.
    by_mp = {}
    for f in features:
        mp = float(verified.get(f["name"], {}).get("milepost", f["mile"]))
        by_mp.setdefault(round(mp, 1), []).append(f["name"])
    nudge = {}
    for mp, names in by_mp.items():
        if len(names) > 1:
            for i, nm in enumerate(sorted(names)):
                nudge[nm] = (i - (len(names) - 1) / 2.0) * 0.03

    moved = []
    for f in features:
        v = verified.get(f["name"], {})
        mp = float(v.get("milepost", f["mile"]))
        placement = v.get("placement", "on_parkway")
        spur = float(v.get("spur_miles", 0) or 0)

        f["mile"] = round(mp, 1)
        geom_mile = calibrate(mono, mp) + nudge.get(f["name"], 0.0)
        lat, lng = point_at_mile(centerline, geom_mile)
        old = (f["lat"], f["lng"])
        f["lat"] = round(lat, 5)
        f["lng"] = round(lng, 5)

        if placement == "off_parkway" and spur > 0:
            f["spur_miles"] = round(spur, 1)
            desc = f.get("desc", "")
            tag = f"About {spur:g} mi off the Parkway"
            if "desc" in f and tag.split()[0] not in desc:
                f["desc"] = (desc + f" ({tag}; marker shows the Parkway access point.)").strip()
        moved.append((hav(old[0], old[1], f["lat"], f["lng"]), f["name"]))

    # verify every feature now lies on the road
    worst = 0.0
    for f in features:
        _, dist = nearest_point(centerline, f["lat"], f["lng"])
        worst = max(worst, dist)
    print(f"max distance from road after placement: {worst * 5280:.0f} ft")
    if worst > 0.05:
        sys.exit(f"ERROR: a feature is still {worst:.2f} mi off the road")

    # mileposts must stay ordered
    for key in ("pois", "junctions"):
        data[key] = sorted(data[key], key=lambda p: p["mile"])

    moved.sort(reverse=True)
    print("\nlargest corrections:")
    for dist, name in moved[:12]:
        print(f"  moved {dist:6.2f} mi  {name}")
    print(f"\nmedian move: {sorted(m[0] for m in moved)[len(moved) // 2]:.2f} mi")

    with open(DATA_FILE, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False, separators=(",", ":"))
    print(f"wrote {len(features)} repositioned features to {DATA_FILE}")


if __name__ == "__main__":
    main()
