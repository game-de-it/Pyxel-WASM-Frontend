#!/usr/bin/env python3
"""Build a pwf runtime bundle from prototype/vendor.

A bundle is the unit the app updates: Pyodide, the Pyxel wheel and the patched
pyxel.js move together, because the wheel is built against one Pyodide ABI and a
mixed pair simply does not boot. bundle.json doubles as the OTA manifest.
"""

import argparse
import hashlib
import json
import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VENDOR = ROOT / "prototype" / "vendor"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("version", type=int, help="bundle number; must increase")
    ap.add_argument("outdir", type=Path, help="directory to write the bundle into")
    ap.add_argument("--vendor", type=Path, default=None, help="source runtime tree")
    args = ap.parse_args()

    vendor = args.vendor or VENDOR

    if not (vendor / "pyodide" / "pyodide-lock.json").is_file():
        sys.exit("prototype/vendor がありません。先に tools/fetch-runtime.sh を実行してください")

    lock = json.loads((vendor / "pyodide" / "pyodide-lock.json").read_text())["info"]
    wheels = list((vendor / "pyxel").glob("pyxel-*.whl"))
    if len(wheels) != 1:
        sys.exit(f"pyxel の wheel が一意に決まりません: {wheels}")
    pyxel_version = re.match(r"pyxel-([\d.]+)-", wheels[0].name).group(1)
    pyodide_version = json.loads((vendor / "pyodide" / "package.json").read_text())["version"]

    out = args.outdir
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)

    layers = sorted(d.name for d in vendor.glob("pyxel-*") if (d / "pyxel.js").is_file())
    for sub in ["pyxel", "pyodide", *layers]:
        shutil.copytree(vendor / sub, out / sub)

    files = {}
    for path in sorted(out.rglob("*")):
        if not path.is_file():
            continue
        rel = path.relative_to(out).as_posix()
        files[rel] = {"sha256": sha256(path), "size": path.stat().st_size}

    # Alternate Pyxel layers ride along in the same bundle. They are only
    # legitimate because they share this Pyodide's abi and platform -- the
    # wheel is what changes, not the interpreter underneath it.
    variants = [{"pyxel": pyxel_version, "dir": "pyxel"}]
    for name in layers:
        wheel = next((vendor / name).glob("pyxel-*.whl"), None)
        if wheel is None:
            sys.exit(f"{name} に wheel がありません")
        variants.append(
            {"pyxel": re.match(r"pyxel-([\d.]+)-", wheel.name).group(1), "dir": name}
        )

    manifest = {
        "bundle": args.version,
        "pyxel": pyxel_version,
        "pyodide": pyodide_version,
        "abi": lock["abi_version"],
        "platform": lock["platform"],
        "variants": variants,
        "files": files,
    }
    (out / "bundle.json").write_text(json.dumps(manifest, indent=1, sort_keys=True))

    total = sum(f["size"] for f in files.values())
    print(f"bundle {args.version}: {len(files)} files, {total / 1e6:.1f} MB -> {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
