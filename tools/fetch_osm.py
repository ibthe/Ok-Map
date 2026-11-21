#!/usr/bin/env python3
"""Downloads an OSM extract of the country of Georgia (.osm.pbf).

Sources tried in order:
  1. $OSM_URL (manual override, e.g. from the workflow's "osm_url" input)
  2. Geofabrik index (official), Geofabrik region pages
  3. openstreetmap.fr country extracts
Anything that is not a real PBF file (HTML error pages, etc.) is rejected.
Usage: fetch_osm.py <out.osm.pbf>"""
import json
import os
import re
import shutil
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

OUT = sys.argv[1]
UA = {"User-Agent": "Mozilla/5.0 (ok-map-build)"}


def get(url, tries=3):
    for i in range(tries):
        try:
            return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120)
        except urllib.error.HTTPError as e:
            print(f"  HTTP {e.code}: {url}")
            if e.code in (403, 404, 410):
                return None
        except Exception as e:  # noqa
            print(f"  error {e!r}: {url}")
        time.sleep(2 * (i + 1))
    return None


def page_links(page, pattern):
    r = get(page, tries=2)
    if r is None:
        return []
    try:
        text = r.read().decode("utf-8", "replace")
    except Exception:
        return []
    return [urllib.parse.urljoin(page, m) for m in re.findall(pattern, text, re.I)]


def candidates():
    urls = []
    env = os.environ.get("OSM_URL", "").strip()
    if env:
        urls.append(env)

    for idx in ("https://download.geofabrik.de/index-v1-nogeom.json",):
        r = get(idx, tries=2)
        if r is None:
            continue
        data = json.load(r)
        for f in data.get("features", []):
            p = f.get("properties", {})
            if p.get("name", "").strip().lower() == "georgia" and not str(p.get("id", "")).startswith("us"):
                print("  geofabrik index entry:", json.dumps(p, ensure_ascii=False)[:600])
                for k, v in (p.get("urls") or {}).items():
                    if k == "pbf":
                        urls.append(v)
                parent = p.get("parent")
                if parent:
                    urls.append(f"https://download.geofabrik.de/{parent}/georgia-latest.osm.pbf")

    for page in ("https://download.geofabrik.de/europe/georgia.html",
                 "https://download.geofabrik.de/europe.html",
                 "https://download.geofabrik.de/asia.html"):
        urls += page_links(page, r'href="([^"]*georgia[^"]*\.osm\.pbf)"')

    for region in ("asia", "europe"):
        urls.append(f"https://download.openstreetmap.fr/extracts/{region}/georgia.osm.pbf")
        urls += page_links(f"https://download.openstreetmap.fr/extracts/{region}/",
                           r'href="(georgia[^"]*\.osm\.pbf)"')

    seen, out = set(), []
    for u in urls:
        if u in seen:
            continue
        seen.add(u)
        low = u.lower()
        if "/us/" in low or "north-america" in low:
            continue
        out.append(u)
    return out


def is_pbf(path):
    with open(path, "rb") as f:
        head = f.read(200)
    return b"OSMHeader" in head


cands = candidates()
print("candidate urls:")
for u in cands:
    print("  ", u)
for u in cands:
    print("downloading", u)
    r = get(u, tries=2)
    if r is None:
        continue
    with open(OUT, "wb") as f:
        shutil.copyfileobj(r, f, 1 << 20)
    size = os.path.getsize(OUT)
    print("  saved", size >> 20, "MB")
    if size > 10 << 20 and is_pbf(OUT):
        print("OK:", u)
        sys.exit(0)
    print("  not a valid PBF, trying next")
sys.exit(
    "ERROR: could not download an OSM extract of Georgia.\n"
    "Find a working .osm.pbf link for the COUNTRY of Georgia (not the US state) and run the workflow\n"
    "again via Actions -> Build APK -> Run workflow, pasting the link into the 'osm_url' field."
)
