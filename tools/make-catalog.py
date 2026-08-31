#!/usr/bin/env python3
"""Survey which Pyxel versions can be installed, and write a catalog.

The launcher lets a game pin its own Pyxel, so it needs to know what exists:
for every tag that ships a wasm build, the wheel's exact filename, the Pyodide
release it was built against, and its ABI tag. Versions whose Pyodide matches
the bundled one install as a wheel-sized layer; the rest bring their own
interpreter.

Run at build time -- the result ships in the APK, so the list works offline.
Fetching goes through curl, because this also runs on machines whose Python
has no usable root certificate store, and a survey where every request fails
looks exactly like a survey where nothing exists.
"""

import argparse
import json
import re
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

REPO = "https://github.com/kitao/pyxel"
RAW = "https://raw.githubusercontent.com/kitao/pyxel"

# Every ABI the wasm builds have used. Probed in order; the first that resolves
# is the one that tag ships.
ABIS = [
    "cp311-abi3-emscripten_5_0_3",
    "cp310-abi3-emscripten_4_0_9",
    "cp38-abi3-emscripten_4_0_9",
    "cp38-abi3-emscripten_3_1_58",
    "cp38-abi3-emscripten_3_1_61",
    "cp37-abi3-emscripten_3_1_45",
    "cp37-abi3-emscripten_3_1_32",
]


def fetch(url, timeout=30):
    out = subprocess.run(
        ["curl", "-sSL", "--max-time", str(timeout), url],
        capture_output=True,
        timeout=timeout + 15,
    )
    return out.stdout if out.returncode == 0 and out.stdout else None


def head_ok(url, timeout=30):
    out = subprocess.run(
        ["curl", "-sIL", "-o", "/dev/null", "-w", "%{http_code}",
         "--max-time", str(timeout), url],
        capture_output=True,
        text=True,
        timeout=timeout + 15,
    )
    return out.stdout.strip() == "200"


def tags():
    """Tag names over the git protocol; the REST API is rate limited per
    address and this survey already makes hundreds of requests to one host."""
    out = subprocess.run(
        ["git", "ls-remote", "--tags", "--refs", REPO],
        capture_output=True, text=True, timeout=180,
    )
    if out.returncode != 0:
        print(out.stderr.strip()[:300], file=sys.stderr)
        return []
    return [line.rsplit("/", 1)[-1] for line in out.stdout.splitlines() if line.strip()]


def survey(tag):
    js = fetch(f"{RAW}/{tag}/wasm/pyxel.js")
    if js is None:
        return None  # no wasm build at this tag
    m = re.search(r"pyodide/v([\d.]+)/", js.decode("utf-8", "replace"))
    if not m:
        return None
    version = tag.lstrip("v")
    for abi in ABIS:
        wheel = f"pyxel-{version}-{abi}_wasm32.whl"
        if head_ok(f"{RAW}/{tag}/wasm/{wheel}"):
            return {"pyxel": version, "wheel": wheel, "abi": abi, "pyodide": m.group(1)}
    print(f"  {version}: wheel の ABI が分かりません", file=sys.stderr)
    return None


def key(version):
    return tuple(int(p) for p in re.findall(r"\d+", version))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out", type=Path)
    ap.add_argument("--min", default="2.0", help="oldest version worth listing")
    args = ap.parse_args()

    floor = key(args.min)
    found_tags = [t for t in tags() if re.fullmatch(r"v\d+\.\d+\.\d+", t) and key(t) >= floor]
    if not found_tags:
        sys.exit("タグを取得できませんでした")
    print(f"{len(found_tags)} タグを調査します", file=sys.stderr)

    with ThreadPoolExecutor(max_workers=8) as pool:
        found = [e for e in pool.map(survey, found_tags) if e]
    if not found:
        sys.exit("wasm ビルドを1つも見つけられませんでした")
    found.sort(key=lambda e: key(e["pyxel"]), reverse=True)

    args.out.write_text(json.dumps({
        "generated": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
        "source": f"{RAW}/<tag>/wasm",
        "versions": found,
    }, indent=1))

    groups = {}
    for e in found:
        groups.setdefault((e["abi"], e["pyodide"]), []).append(e["pyxel"])
    print(f"{len(found)} 版 -> {args.out}", file=sys.stderr)
    for (abi, pyodide), vs in groups.items():
        print(f"  {abi:34} pyodide {pyodide:8} {vs[0]} .. {vs[-1]} ({len(vs)})", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
