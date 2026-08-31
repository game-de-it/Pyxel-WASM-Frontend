#!/usr/bin/env python3
"""Make one pyxel.js load the bundled runtime instead of a CDN.

Split out of fetch-runtime.sh once more than one Pyxel layer had to be patched.
Every edit is idempotent, so re-running over an already patched tree is a no-op.
"""

import re
import sys
from pathlib import Path


def main() -> int:
    p = Path(sys.argv[1])
    s = p.read_text()

    # 1. Load the bundled Pyodide instead of the jsDelivr CDN. Matched by shape
    #    rather than by literal: every pyxel tag names its own Pyodide release.
    bundled = 'const PYODIDE_URL = "../pyodide/pyodide.js";  // pwf: bundled offline runtime'
    if bundled not in s:
        s, hits = re.subn(
            r'const PYODIDE_URL = "https://cdn\.jsdelivr\.net/pyodide/v[\d.]+/full/pyodide\.js";',
            bundled,
            s,
        )
        if hits != 1:
            sys.exit(f"PYODIDE_URL の書き換え先が {hits} 件見つかりました: {p}")

    patches = [
        # 2. That URL becomes a <script src>, which resolves against the
        #    document, not against pyxel.js. Anchor it so the vendor tree stays
        #    relocatable -- which is also what lets layers sit side by side.
        (
            "  await _loadScript(PYODIDE_URL);",
            "  await _loadScript(_scriptDir + PYODIDE_URL);  // pwf: resolve against vendor dir",
        ),
        # 3. The startup banner parses a version out of PYODIDE_URL; a local
        #    path has none, and the unguarded match() throws before Pyodide
        #    even loads.
        (
            r'  const pyodideVersion = PYODIDE_URL.match(/v([\d.]+)\//)[1];',
            r'  const pyodideVersion = PYODIDE_URL.match(/v([\d.]+)\//)?.[1] ?? "bundled";  // pwf',
        ),
        # 4. A seam for putting files in the working directory before the app
        #    runs. Games shipped as "a .pyxapp plus a data folder" read that
        #    folder from the current directory, and the built-in lazy fetch
        #    cannot enumerate a directory -- so the host materialises the whole
        #    tree here instead.
        (
            "  _copyFileFromBase64(pyodide, params.name, params.base64);",
            "  _copyFileFromBase64(pyodide, params.name, params.base64);\n"
            "  await window.pwfPrepareFiles?.(pyodide, params);  // pwf",
        ),
    ]

    for old, new in patches:
        if new in s:
            continue
        if old not in s:
            sys.exit(f"patch target not found (pyxel.js changed upstream?):\n  {old}\n  {p}")
        s = s.replace(old, new)

    p.write_text(s)
    print("patched", p.parent.name)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
