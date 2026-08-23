#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""
Fetches every fritkot / frituur / friterie in Belgium (Brussels, Flanders,
and Wallonia alike) from OpenStreetMap via the Overpass API, and writes
them into app/src/main/assets/fritkots_fallback.json in the format the
Android app expects.

This is run automatically by .github/workflows/build.yml before every
build, so the bundled offline dataset is refreshed from OSM on each
release rather than hand-maintained. You can also run it yourself:

    python3 scripts/fetch_fritkots.py

It needs a real internet connection (this is precisely the step the build
sandbox that originally scaffolded this project could not do itself, since
that environment's network is locked down to a small allow-list that
excludes OpenStreetMap's services entirely).

Design notes:
  - Queries the whole of Belgium in one go via the country's OSM relation
    (ISO3166-1=BE, admin_level=2), so Brussels, Flanders and Wallonia are
    all covered by construction, not by enumerating cities.
  - Matches amenity=fast_food with cuisine=friture/frites/fries (the usual
    OSM tagging for a Belgian fry shop), plus a name-based fallback for
    the many fritkots that are tagged fast_food without a cuisine tag.
  - Tries several public Overpass mirrors in turn, since any one of them
    can be temporarily overloaded or rate-limiting.
  - On failure, leaves the existing fritkots_fallback.json untouched and
    exits 0 (so a flaky Overpass day never breaks the app build) — but
    prints a clear warning either way.
"""

import json
import sys
import urllib.request
import urllib.error
from pathlib import Path

MIRRORS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.osm.ch/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
]

QUERY = """
[out:json][timeout:180];
area["ISO3166-1"="BE"][admin_level=2]->.be;
(
  node["amenity"="fast_food"]["cuisine"~"friture|frites|fries",i](area.be);
  node["amenity"="fast_food"]["name"~"friture|frituur|fritkot|frite|frietkot",i](area.be);
);
out body;
""".strip()

OUTPUT_PATH = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "fritkots_fallback.json"

# Sanity floor: OSM has well over a thousand Belgian fry shops tagged at
# time of writing. If a mirror returns a suspiciously small result (e.g.
# a truncated or partial response), don't overwrite a good existing file
# with a worse one.
MIN_SANE_COUNT = 200


def fetch_from(base_url: str) -> dict:
    data = ("data=" + QUERY).encode("utf-8")
    req = urllib.request.Request(
        base_url,
        data=data,
        headers={
            "Content-Type": "application/x-www-form-urlencoded",
            "User-Agent": "FritkotCompass/1.0 (offline dataset builder; https://github.com/)",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=200) as resp:
        return json.loads(resp.read().decode("utf-8"))


def build_address(tags: dict) -> str:
    street = tags.get("addr:street", "")
    number = tags.get("addr:housenumber", "")
    city = tags.get("addr:city", "")
    parts = []
    if street:
        parts.append(f"{street} {number}".strip() if number else street)
    if city:
        parts.append(city)
    return ", ".join(parts)


def parse_elements(osm_json: dict) -> list:
    results = []
    seen_ids = set()
    for el in osm_json.get("elements", []):
        if el.get("type") != "node":
            continue
        node_id = el.get("id")
        lat = el.get("lat")
        lon = el.get("lon")
        if node_id is None or lat is None or lon is None:
            continue
        if node_id in seen_ids:
            continue
        seen_ids.add(node_id)
        tags = el.get("tags", {}) or {}
        name = tags.get("name") or None
        results.append({
            "id": node_id,
            "name": name,
            "lat": lat,
            "lon": lon,
            "address": build_address(tags),
        })
    return results


def main() -> int:
    last_error = None
    for base_url in MIRRORS:
        print(f"Trying {base_url} ...")
        try:
            osm_json = fetch_from(base_url)
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError) as e:
            print(f"  failed: {e}")
            last_error = e
            continue
        except json.JSONDecodeError as e:
            print(f"  failed: bad JSON ({e})")
            last_error = e
            continue

        fritkots = parse_elements(osm_json)
        print(f"  got {len(fritkots)} fritkots")

        if len(fritkots) < MIN_SANE_COUNT:
            print(f"  fewer than {MIN_SANE_COUNT} results, treating as a partial/bad response, trying next mirror")
            continue

        fritkots.sort(key=lambda f: (f["name"] or "").lower())
        OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT_PATH.write_text(json.dumps(fritkots, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"Wrote {len(fritkots)} fritkots to {OUTPUT_PATH}")
        return 0

    print(f"WARNING: could not fetch a full dataset from any Overpass mirror ({last_error}).")
    print(f"Leaving the existing {OUTPUT_PATH} untouched.")
    return 0  # never fail the build over this


if __name__ == "__main__":
    sys.exit(main())
