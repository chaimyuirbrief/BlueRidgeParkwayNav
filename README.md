# Parkway Nav — Blue Ridge Parkway Navigation

An Android (Kotlin / Jetpack Compose) turn-by-turn navigation app built specifically for the
**Blue Ridge Parkway**. Unlike general map apps, which leave the Parkway at the first faster
road, Parkway Nav **keeps you on the Parkway as long as possible** and only routes you off at
the access junction nearest your destination.

## Why this app exists

Google Maps and similar apps optimize for time, so the moment a quicker highway appears they
pull you off the Parkway. Parkway travelers want the opposite: stay on the Blue Ridge Parkway,
enjoy the drive, and only exit at the right junction. This app does that.

**Privacy:** the developer collects **no data**. No analytics, no trackers, no accounts.
Everything — routes, settings, location — stays on your device. The only time data leaves the
device is when *you* explicitly export a backup to a folder you choose, or when the Google
Maps/Places/Directions APIs are queried for the off-Parkway portions of a route and address
search (these calls go directly from your device to Google under your own API key).

## Features

- **Stay-on-Parkway routing engine** (`routing/BrpRouter.kt`) driven by the NPS Parkway
  centerline. From a point on the Parkway to any destination, it rides the Parkway to the
  junction closest to the destination, then uses normal roads only for the final leg.
- **From / To with current-location default** and **address autocomplete** (Google Places +
  offline Parkway POI suggestions).
- **Multi-stop route planning** and **saving routes**, including pinning a route as a
  **home-screen shortcut** that launches navigation directly.
- **Driving view**: navigation arrow points in the direction of travel, the map tilts forward,
  on-screen buttons toggle **voice guidance** and **current speed**, and the **live mile
  marker** and **local ZIP code** are shown in real time.
- **Overlooks & attractions** from the National Park Service (`nps.gov/blri`) — visitor
  centers, overlooks, campgrounds, tunnels, trailheads, lodges, and more.
- **Settings**
  - *General*: appearance (Light / Dark / System — **defaults to Dark**), 24-hour time,
    display language (English / Español, more to come), keep-screen-on with an ignore option.
  - *Navigation*: battery-optimization exemption, run-in-background, picture-in-picture.
  - *Voice & sound*: voice type (male / female / default), speaking-speed slider, play over
    Bluetooth, play during calls, mute-on-open, set-volume-on-open with a volume slider.
  - *Backup & restore*: export/import settings + saved routes to a folder you choose, auto
    backup, optional **device-admin** protection, an app removal lock reminder, and an in-app
    **uninstall** button that warns you to back up first and automatically disables device
    admin before uninstalling. Restoring a backup that had location enabled prompts you to
    re-enable location.
  - *About*: why the app exists and the privacy promise.

## Building

The app needs a Google Maps Platform API key with **Maps SDK for Android**, **Places API**,
and **Directions API** enabled.

### CI (GitHub Actions)
The key is read from the repository secret **`maps_api_key`**. The workflow in
`.github/workflows/android.yml` exposes it as the `MAPS_API_KEY` environment variable and runs
`gradle :app:assembleDebug`. The debug APK is uploaded as a build artifact.

### Local
Add to `local.properties` (which is git-ignored):

```
MAPS_API_KEY=your_key_here
```

or export `MAPS_API_KEY` in your environment, then build with Android Studio or
`./gradlew assembleDebug`.

## Data

Parkway geometry and points of interest live in `app/src/main/assets/brp_data.json`
(centerline anchor points by milepost, POIs, and access junctions), compiled from the
National Park Service (`nps.gov/blri`). The centerline is a polyline of anchor points; dropping
in higher-resolution NPS GIS centerline data improves routing fidelity with no code changes.

> Note: after Hurricane Helene (2024) several NC Parkway sections have had closures/repairs.
> Always check the NPS real-time closures map before a trip.

## Project layout

```
app/src/main/java/com/blueridge/parkwaynav/
├── data/        BRP dataset, settings (DataStore), saved routes, backup/restore
├── routing/     GeoUtils, Directions client, BrpRouter (the stay-on-Parkway engine)
├── nav/         location engine, TTS, foreground service, active-route holder
├── places/      Places autocomplete + offline POI suggestions
├── admin/       optional device-admin receiver + uninstall helper
├── di/          tiny manual service locator
├── util/        locale, map bitmaps, home-screen shortcuts
└── ui/          Compose screens (home, planner, navigation, overlooks, settings, about)
```
