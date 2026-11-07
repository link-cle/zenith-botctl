#!/usr/bin/env python3
"""Builds an index of rendered map images for the phone app.

Why this exists: ZenithProxy writes rendered maps as PNGs into
/root/ZenithProxy/maps/, and the WebUI plugin can serve any file under its
webDir over the existing 8080 port (there is a symlink
plugins/webui/web/maps -> ../maps). But the plugin's static handler caches
files by path for the lifetime of the process, so a single mutable
index.json would go stale forever.

Workaround: every run writes a *fresh filename* carrying a 20-second time
stamp (index_<stamp>.json). New paths are never cached, so the app always
sees current data. The app probes a few nearby stamps, using a clock offset
it learns from the 'generated' field.

Run by mapindex.timer every 20 seconds.
"""
import json
import os
import time

MAPS_DIR = "/root/ZenithProxy/maps"
BUCKET_SECONDS = 20
KEEP_BUCKETS = 60          # ~20 minutes of index files


def build_payload():
    files = []
    try:
        names = os.listdir(MAPS_DIR)
    except FileNotFoundError:
        os.makedirs(MAPS_DIR, exist_ok=True)
        names = []
    for name in names:
        if not name.endswith(".png"):
            continue
        path = os.path.join(MAPS_DIR, name)
        try:
            st = os.stat(path)
        except OSError:
            continue
        files.append({"n": name, "t": int(st.st_mtime), "b": st.st_size})
    # newest first; the app takes the first contiguous batch as "the latest render"
    files.sort(key=lambda x: (-x["t"], x["n"]))
    return {"files": files[:300], "generated": int(time.time()), "bucket": BUCKET_SECONDS}


def write_atomic(path, raw):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        fh.write(raw)
    os.replace(tmp, path)


def prune(current_stamp):
    for name in os.listdir(MAPS_DIR):
        if not (name.startswith("index_") and name.endswith(".json")):
            continue
        try:
            stamp = int(name[len("index_"):-len(".json")])
        except ValueError:
            continue
        if stamp < current_stamp - KEEP_BUCKETS:
            try:
                os.remove(os.path.join(MAPS_DIR, name))
            except OSError:
                pass


def main():
    payload = build_payload()
    raw = json.dumps(payload)
    stamp = int(time.time() // BUCKET_SECONDS)
    write_atomic(os.path.join(MAPS_DIR, "index_%d.json" % stamp), raw)
    # legacy stable name: may be cached by the plugin, kept only as a fallback
    write_atomic(os.path.join(MAPS_DIR, "index.json"), raw)
    prune(stamp)


if __name__ == "__main__":
    main()
