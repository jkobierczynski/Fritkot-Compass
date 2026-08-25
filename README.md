# Fritkot Compass

A small native Android app: point your phone and an arrow shows you the direction
and distance to the nearest fritkot / frituur / friterie in Belgium, using live
data from OpenStreetMap.

![Fritkot Compass screenshot](Fritkot-Compass.jpg)

- **Compass**: rotates with your phone (rotation vector sensor) and always points
  toward the nearest fritkot, not north.
- **Distance**: shown in metres/kilometres, updates as you move.
- **Live data**: queries the public [Overpass API](https://overpass-api.de) for
  OSM nodes tagged as Belgian fry shops (`amenity=fast_food` + `cuisine=friture`,
  with a name-based fallback for untagged ones) within 30 km, re-querying as you
  move more than 300 m.
- **Offline fallback**: if there's no connection, a bundled dataset
  (`app/src/main/assets/fritkots_fallback.json`) is used instead so the app
  still shows something — see [Offline dataset](#offline-dataset) below for
  how it covers all of Belgium, not just one region. The app only ever
  *reads* this file at runtime — it's packaged inside the APK as a read-only
  asset, so nothing the app does while running can change or overwrite it;
  refreshing it is a separate, deliberate step you take (see below), not
  something that happens as a side effect of using the app.
- **Interactive map**: a second screen ("Pick location on map") with a
  draggable, pinch-to-zoom OpenStreetMap view — tap anywhere to search for
  the nearest fritkots to that point instead of your current GPS location.
  See [Interactive map](#interactive-map) below.
- **Open now / closing soon / closed**: when OSM has opening-hours data for
  a fritkot, its name (and its entry in the nearby list) turns orange if
  it's about to close and red if it's already closed. See
  [Opening hours](#opening-hours) below for what "known" depends on.
- **Minimal dependencies**: no AndroidX, no Google Play Services, no
  network/JSON libraries. The one exception is [osmdroid](#interactive-map)
  for the map screen, since there's no reasonable way to hand-roll a
  pinch-zoomable slippy map from scratch.
- **Dutch, French and English** strings included, since it's a Belgian app.

## Getting the installable APK

This project builds automatically via **GitHub Actions** every time it's
pushed — GitHub's build servers do the actual compiling, so you don't need
Android Studio installed anywhere. That's the recommended way to get a real
APK onto your phone (see *why* below).

1. Create a new **public or private** repository on GitHub (an empty one —
   don't initialize it with a README).
2. Upload this project's contents to it. Easiest ways:
   - **No git needed**: on the new repo's page, click **"uploading an existing
     file"**, then drag the whole extracted folder's contents in. GitHub
     supports dragging a folder in Chrome/Edge.
   - **With git**, from inside the extracted folder:
     ```
     git init
     git add .
     git commit -m "Fritkot Compass"
     git branch -M main
     git remote add origin https://github.com/<your-username>/<your-repo>.git
     git push -u origin main
     ```
3. Go to the repo's **Actions** tab. A "Build APK" run starts automatically
   and finishes in a couple of minutes. Open it, scroll to **Artifacts**, and
   download `fritkot-compass-debug-apk` — that's a real, signed, installable
   APK (zipped; unzip it to get `app-debug.apk`).
4. To get a **permanent shareable download link** instead of an Actions
   artifact (artifacts expire after 90 days), create a tag and push it —
   this makes the workflow publish a GitHub **Release** with the APK attached:
   ```
   git tag v1.0.0
   git push origin v1.0.0
   ```
   The APK will then sit at a stable URL under your repo's **Releases** page
   that you can send to anyone.
5. On your Android phone, download the APK (e.g. open the Release link in
   Chrome) and tap it to install. You'll need to allow "install unknown apps"
   for your browser the first time — Android will prompt you for this
   automatically.

Every future push to `main` rebuilds the APK the same way, signed with the
same key (see below), so re-installing an updated version works cleanly.

### Why GitHub Actions, rather than a prebuilt APK attached here

Compiling a modern Android app needs Google's Android SDK and Maven
repositories, which the environment this project was built in cannot reach
(they're not on its network allow-list). Everything in this project — all
the Kotlin source, resources, and the Gradle build files — has already been
verified locally: the resources were compiled with `aapt` and the Kotlin
source was fully type-checked against a real Android platform SDK (including
resolving every `R.id` / `R.string` / `R.layout` reference), so there's very
high confidence it's correct. What couldn't be done in that environment is
the final packaging step (turning compiled classes into a signed `.apk`),
which needs Google's dependencies to download. GitHub Actions runners have
normal, unrestricted internet access, so pushing this project there and
letting the included workflow build it is genuinely the most reliable way to
get a working APK — and it means every future change gets rebuilt the same
way automatically.

If you'd rather build it locally: open the project folder in Android Studio
(Giraffe or newer) and click Run, or from a terminal with the Android SDK
installed run `./gradlew assembleDebug`.

## Interactive map

`MapActivity.kt` (opened from the main screen's "Pick location on map"
button) is a full OpenStreetMap view built on
[osmdroid](https://github.com/osmdroid/osmdroid) — drag to pan, pinch to
zoom, and a single tap drops a pin and searches from that point instead of
your GPS location, showing (and marking on the map) the nearest fritkots to
wherever you tapped. It reuses the exact same `OverpassClient` — including
the offline/online toggle — as the main screen, just centred on the tapped
point instead of your live location.

osmdroid is this project's only real dependency — writing a pannable, zoomable map
renderer with its own tile loading and caching from scratch isn't a
reasonable thing to do by hand, and osmdroid is a mature, actively
maintained, Apache-2.0-licensed library built for exactly this. It's
compatible with this project's GPLv3 license (Apache-2.0 code can be
included in a GPLv3 work), and it renders standard OpenStreetMap tiles,
attributed on-screen via osmdroid's built-in copyright overlay, per OSM's
usage policy.

One honest caveat: everything else in this project could be verified inside
the sandbox this was built in — resources compiled with `aapt`, and Kotlin
fully type-checked against a real Android platform SDK. That sandbox's
network is locked down to a small allow-list that excludes Maven Central,
so osmdroid's actual library code could never be downloaded there, and
`MapActivity.kt` could only be checked for plain Kotlin syntax errors, not
fully type-checked against osmdroid's real API the way the rest of the
codebase was. The method and class names used (`MapView`, `GeoPoint`,
`Marker`, `MapEventsOverlay`, `Configuration`, etc.) were cross-checked
against osmdroid's actual source on GitHub rather than relied on from
memory, which gives good confidence — but GitHub Actions' first build of
this file is the real test. If it fails, the error log will point straight
at whatever's off, and it's very likely a small one-line fix (a slightly
different method name or import) rather than anything structural.

## Opening hours

A fritkot's name (and its row in the "nearby" list, on both the main screen
and the map screen) turns **orange** if it's open but closing within 30
minutes, and **red** if it's currently closed — with a short label
("Closing soon (12 min)" / "Closed now") next to it. If neither applies —
it's open with no closing time imminent, or its status simply isn't known —
nothing changes; it stays the normal color.

This is driven entirely by OSM's `opening_hours` tag, which `OverpassClient`
now reads from live query results and `fetch_fritkots.py` now captures into
the offline dataset. **Not every OSM node has this tag** — a lot of small
fritkots simply have no recorded hours — and untagged ones always show as
"unknown" (no color), never guessed at. Coverage depends entirely on how
complete OpenStreetMap's data is for a given fritkot; anyone can improve it
by editing the node on [openstreetmap.org](https://www.openstreetmap.org).

The actual interpreting is done on-device by `OpeningHours.kt`, a small,
deliberately pragmatic reader of the `opening_hours` mini-language — not the
full [OSM opening_hours spec](https://wiki.openstreetmap.org/wiki/Key:opening_hours),
which also covers public/school holidays, month and week-of-year ranges,
and more (that's genuinely a project of its own — there's a dedicated
`opening_hours.js` library for exactly this reason). It handles the
patterns that cover the vast majority of small food shops: `24/7`; weekday
selectors like `Mo-Fr`, `Sa,Su`, or a single day; comma-separated time
ranges per day (`11:30-14:00,17:30-22:00`); ranges that cross midnight
(`20:00-02:00`); `off`/`closed`; and `;`-separated rules, with a later rule
overriding an earlier one for the same day (so `Tu-Su 11:00-22:00; Mo off`
behaves as expected). Anything using syntax outside that subset — `PH`,
`SH`, month names, week numbers, parenthesised comments — is deliberately
treated as *unknown* rather than misread, since showing no status is far
better than confidently showing the wrong one for a real business's hours.
Verified against a small set of hand-written test cases (including the
overnight-crossing and rule-override behavior) run against the real
implementation before this was shipped.

## Offline dataset

The app's primary data source is always the live Overpass query — the
offline dataset only kicks in when the phone has no connection. Belgium has
no single official fritkot registry, so this dataset comes from
OpenStreetMap, the same source the live query uses.

**`scripts/fetch_fritkots.py`** queries Overpass for *every* node in Belgium
tagged as a fry shop — one country-wide query (via OSM's `ISO3166-1=BE`
area), so Brussels, Flanders and Wallonia are all covered by construction
rather than by listing cities by hand. It writes the result straight into
`app/src/main/assets/fritkots_fallback.json` in the format the app expects
(name, coordinates, address, opening hours where OSM has them).

The environment this project was originally scaffolded in has a locked-down
network that can't reach OpenStreetMap's services at all, so it could only
ship a small, hand-picked seed (a few well-known Brussels fritkots plus
generic placeholder pins for the other provincial capitals) as the in-repo
starting point — replace it with the real dataset using one of the two
options below (typically well over a thousand entries once refreshed).

**The build itself (`build.yml`) does not re-fetch this on every push or
release** — it just packages whatever `fritkots_fallback.json` is already
committed, so ordinary builds are fast and don't depend on Overpass being up.
Refreshing the dataset is a separate, deliberate step, since fritkots don't
open/close often enough to need refetching on every release. Two ways to do
it:

1. **Run it yourself, whenever you like**, from any machine with normal
   internet access, then commit the result like any other change:
   ```
   python3 scripts/fetch_fritkots.py
   git add app/src/main/assets/fritkots_fallback.json
   git commit -m "chore: refresh offline fritkot dataset"
   git push
   ```
2. **Trigger the "Refresh offline dataset" GitHub Actions workflow**
   (`.github/workflows/refresh-dataset.yml`) from the repo's Actions tab —
   click it, hit "Run workflow", and it fetches and commits the refreshed
   file for you, no local Python needed. It also runs automatically on the
   1st of each month by default; delete the `schedule:` block in that file
   if you'd rather it only ever run when you trigger it by hand.

## Signing

`debug.keystore` at the repo root is a fixed (non-secret) signing key checked
into the project so every CI build produces an APK with the *same* signature —
without this, each GitHub Actions run would generate a random debug key and
a new build would refuse to install over an older one. This is fine for
sharing an app directly; it is **not** meant for submitting to the Google
Play Store (Play requires you to generate and keep your own private release
key).

## Publishing further

- **Direct APK download** (what's set up here): share the GitHub Release
  link. Anyone can install it after allowing "unknown apps" for their
  browser once. No account or fee needed.
- **Google Play Store**: requires your own Google Play Developer account
  (one-time $25 fee, identity verification) since only you can create and
  own that account. If you want this later, the release APK build in the
  Actions workflow is the starting point — it would need a real release
  signing key instead of the debug one above, and a store listing.
- **F-Droid**: a free, no-account open-source app store. Submission is a
  pull request to F-Droid's own repo under your GitHub account, and review
  can take days to weeks — worth doing later if you want wider organic
  reach without a Play account.

## Privacy

Your location is used only on-device to compute distance/bearing and as the
centre point of the Overpass query — it isn't sent anywhere except as
latitude/longitude in that query to the Overpass API (a public OpenStreetMap
service), and nothing is stored or tracked by the app itself. The same
applies to a point you tap on the map screen: those coordinates are used the
same way, as the search centre, and nothing else. The map screen also
requests map tile images from OpenStreetMap's tile servers for whatever area
is currently in view (a normal part of how any map view works), which are
cached on-device in the app's own cache folder.

## Project layout
 
```
app/src/main/java/be/fritkot/compass/
  MainActivity.kt     – permissions, location updates, sensor handling, UI wiring
  MapActivity.kt        – interactive OSM map screen (osmdroid)
  CompassView.kt          – the custom compass dial + arrow drawing
  OverpassClient.kt         – Overpass API query + offline fallback
  OpeningHours.kt              – opening_hours parsing (open/closing soon/closed)
  GeoUtils.kt                 – haversine distance / bearing math
  Fritkot.kt                    – data classes
app/src/main/assets/fritkots_fallback.json  – offline dataset (see Offline dataset)
app/src/main/res/                             – layout, strings (en/nl/fr), colors, icon
scripts/fetch_fritkots.py                       – (re)generates the offline dataset from OSM
.github/workflows/build.yml                       – CI build (builds + release-on-tag only)
.github/workflows/refresh-dataset.yml               – refreshes the dataset (manual or monthly)
```

## License

Licensed under the [GNU General Public License v3.0](LICENSE) or later.
That means anyone is free to use, study, modify, and redistribute this app,
including commercially — but any redistributed version (modified or not)
must also be licensed under the GPL and come with its source code. See the
[`LICENSE`](LICENSE) file for the full terms.

This app depends on [osmdroid](https://github.com/osmdroid/osmdroid),
licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
(compatible with GPLv3), and displays map data and tile imagery from
[OpenStreetMap](https://www.openstreetmap.org/copyright), © OpenStreetMap
contributors, available under the Open Database License.
