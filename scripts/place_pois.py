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
BIN_MI = 40           # width of each drift-smoothing bin, miles
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


def build_calibration(centerline, features, verified):
    """Model posted-milepost -> geometry-milepost drift, smoothed.

    Raw anchors are noisy, and forcing the anchor curve itself to be monotonic
    flattens whole stretches - which collapses distinct nearby features (e.g.
    Price Lake 296.7 / Julian Price 297.1) onto the same coordinate. Instead we
    model the DRIFT (geometry mile minus posted mile), which varies slowly, and
    smooth it into sparse knots. Adding a slowly-varying drift preserves the
    real spacing between neighbouring features.
    """
    raw = []
    for f in features:
        # Use the VERIFIED milepost: a feature whose posted milepost was simply wrong
        # (e.g. US-220 Roanoke, 112.2 -> 121.4) would otherwise inject a bogus drift.
        mp = float(verified.get(f["name"], {}).get("milepost", f["mile"]))
        best, dist = nearest_point(centerline, f["lat"], f["lng"])
        if dist <= TRUST_RADIUS_MI and abs(best["mile"] - mp) <= MAX_ANCHOR_DRIFT_MI:
            raw.append((mp, float(best["mile"]) - mp))
    raw.sort()
    lo = min(c["mile"] for c in centerline)
    hi = max(c["mile"] for c in centerline)

    knots = [(lo, 0.0)]
    for start in range(0, int(hi) + BIN_MI, BIN_MI):
        chunk = [d for m, d in raw if start <= m < start + BIN_MI]
        if chunk:
            chunk.sort()
            median = chunk[len(chunk) // 2]
            knots.append((start + BIN_MI / 2.0, median))
    knots.append((hi, 0.0))
    knots.sort()

    # Guarantee the resulting map stays increasing: drift slope must exceed -1.
    safe = [knots[0]]
    for m, d in knots[1:]:
        pm, pd = safe[-1]
        span = m - pm
        if span > 0:
            min_d = pd - 0.9 * span
            if d < min_d:
                d = min_d
        safe.append((m, d))
    return safe


def calibrate(knots, mp):
    """posted milepost -> geometry milepost via smoothed drift."""
    if mp <= knots[0][0]:
        drift = knots[0][1]
    elif mp >= knots[-1][0]:
        drift = knots[-1][1]
    else:
        drift = knots[-1][1]
        for i in range(len(knots) - 1):
            m0, d0 = knots[i]
            m1, d1 = knots[i + 1]
            if m0 <= mp <= m1:
                t = 0.0 if m1 == m0 else (mp - m0) / (m1 - m0)
                drift = d0 + (d1 - d0) * t
                break
    return mp + drift


def snap_to_polyline(centerline, lat, lng):
    """Nudge a point onto the nearest place on the road line itself (not a vertex),
    so linear interpolation across a sharp curve cannot leave it off the pavement."""
    best_i = min(
        range(len(centerline)),
        key=lambda i: (centerline[i]["lat"] - lat) ** 2 + (centerline[i]["lng"] - lng) ** 2,
    )
    best = (lat, lng)
    best_d = float("inf")
    for i in range(max(0, best_i - 3), min(len(centerline) - 1, best_i + 3)):
        a, b = centerline[i], centerline[i + 1]
        dx = b["lat"] - a["lat"]
        dy = b["lng"] - a["lng"]
        L = dx * dx + dy * dy
        t = 0.0 if L == 0 else max(0.0, min(1.0, ((lat - a["lat"]) * dx + (lng - a["lng"]) * dy) / L))
        px, py = a["lat"] + dx * t, a["lng"] + dy * t
        d = hav(lat, lng, px, py)
        if d < best_d:
            best_d, best = d, (px, py)
    return best


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

    mono = build_calibration(centerline, features, verified)
    print(f"calibration knots: {len(mono)}  drift " + ", ".join(f"{m:.0f}:{d:+.2f}" for m, d in mono))

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
    kept = []
    for f in features:
        v = verified.get(f["name"], {})
        mp = float(v.get("milepost", f["mile"]))
        placement = v.get("placement", "on_parkway")
        spur = float(v.get("spur_miles", 0) or 0)

        f["mile"] = round(mp, 1)
        old = (f["lat"], f["lng"])

        # If the existing coordinate ALREADY lands on the road and agrees with the
        # milepost, it is real survey-grade data - keep it and just snap it flush to
        # the line. Only features whose coordinate is demonstrably wrong get rebuilt
        # from the milepost. Off-Parkway destinations are always rebuilt, because we
        # want the marker at the Parkway access point, not out on the spur.
        best, dist = nearest_point(centerline, old[0], old[1])
        trusted = (
            placement != "off_parkway"
            and dist <= TRUST_RADIUS_MI
            and abs(best["mile"] - mp) <= MAX_ANCHOR_DRIFT_MI
        )
        if trusted:
            lat, lng = snap_to_polyline(centerline, old[0], old[1])
            kept.append(f["name"])
        else:
            geom_mile = calibrate(mono, mp) + nudge.get(f["name"], 0.0)
            lat, lng = point_at_mile(centerline, geom_mile)
            lat, lng = snap_to_polyline(centerline, lat, lng)
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
    worst, worst_name = 0.0, ""
    for f in features:
        px, py = snap_to_polyline(centerline, f["lat"], f["lng"])
        dist = hav(f["lat"], f["lng"], px, py)
        if dist > worst:
            worst, worst_name = dist, f["name"]
    print(f"max distance from the road line: {worst * 5280:.1f} ft ({worst_name})")
    if worst * 5280 > 60:
        sys.exit(f"ERROR: {worst_name} is {worst * 5280:.0f} ft off the road line")

    same = {}
    for f in features:
        same.setdefault((f["lat"], f["lng"]), []).append(f["name"])
    stacked = [v for v in same.values() if len(v) > 1]
    if stacked:
        sys.exit(f"ERROR: markers share an exact position: {stacked}")

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
