#!/usr/bin/env python3
"""Merge duplicate <declare-styleable> declarations the way AGP's resource merger does.

WHY THIS EXISTS
---------------
`aapt2 link` on its own rejects a styleable that two libraries declare with
different attribute sets:

    res/material-1.12.0/values/values.xml:8295: error: resource
    'styleable/SearchView' has a conflicting value for configuration ().
    res/appcompat-1.7.0/values/values.xml:2615: note: originally defined here.

Gradle never hits this because the resource MERGER runs first and collapses all
library res/ into one directory, unioning same-named styleables. A no-Gradle build
hands aapt2 the per-library res/ dirs directly, so the merge has to be reproduced.

The union is not a guess: appcompat declares SearchView with 17 attrs and material
with 12, and the reference APK's own R$styleable.SearchView has 29 fields —
identical in BOTH packages. So the merged result is the union, and both packages'
R classes point at it.

Six of material's attrs (searchPrefixText, useDrawerArrowDrawable,
animateNavigationIcon, animateMenuItems, autoShowKeyboard, backHandlingEnabled)
exist ONLY inside that styleable block. Deleting the block to dodge the conflict —
the obvious first instinct — silently drops those six from the resource table and
leaves material's R$attr six entries short. Union, never delete.

WHAT IT DOES
------------
For every styleable name declared in more than one values file: keep the first
occurrence in a deterministic order, rewrite it to the union of all attribute
elements (deduplicated by attribute name, each element's own text preserved so
`format=` declarations are not lost), and remove the later duplicates. Idempotent —
a second run finds nothing declared twice and changes nothing.

Attribute ORDER inside the block does not matter: aapt2 sorts styleable arrays by
attribute id, and gen_r.py reads the arrays and their index constants back out of
the FINAL linked table rather than trusting any AAR's R.txt.

usage: fix_styleable_union.py <res-dir> [--dry-run]
"""
import glob
import os
import re
import sys

BLOCK = re.compile(r'<declare-styleable\s+name="([^"]+)"\s*>(.*?)</declare-styleable>', re.S)
ATTR = re.compile(r'<attr\b[^>]*/>|<attr\b[^>]*>.*?</attr>', re.S)
ATTR_NAME = re.compile(r'\bname="([^"]+)"')


def attr_elements(body):
    """Attribute elements of a styleable body, keyed by attribute name."""
    out = {}
    for m in ATTR.finditer(body):
        nm = ATTR_NAME.search(m.group(0))
        if nm:
            out.setdefault(nm.group(1), m.group(0).strip())
    return out


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    dry = '--dry-run' in sys.argv
    if not args:
        print(__doc__.strip().splitlines()[-1])
        return 2
    root = args[0]

    files = sorted(glob.glob(os.path.join(root, '*', 'values*', 'values*.xml')))
    if not files:
        print(f"fix_styleable_union: no values files under {root}", file=sys.stderr)
        return 1

    # name -> list of (file, span, attrs-in-order)
    decls = {}
    for f in files:
        s = open(f, errors='ignore').read()
        for m in BLOCK.finditer(s):
            decls.setdefault(m.group(1), []).append((f, m.span(), attr_elements(m.group(2))))

    dupes = {n: v for n, v in decls.items() if len(v) > 1}
    if not dupes:
        print("fix_styleable_union: no styleable is declared by more than one library (nothing to do)")
        return 0

    # Only differing sets actually break aapt2, but unioning identical ones is a
    # no-op, so handle every duplicate uniformly.
    edits = {}   # file -> list of (start, end, replacement-or-None)
    for name, occ in sorted(dupes.items()):
        union = {}
        for _, _, attrs in occ:
            for k, v in attrs.items():
                union.setdefault(k, v)
        keep_file, keep_span, _ = occ[0]
        indent = '    '
        # Each union member keeps its ORIGINAL element text, so an attr that was
        # declared with format="..." still defines it rather than becoming a bare
        # reference to an attr that may not exist.
        body = '\n'.join(f'{indent}{v}' for v in union.values())
        merged = f'<declare-styleable name="{name}">\n{body}\n  </declare-styleable>'
        edits.setdefault(keep_file, []).append((keep_span[0], keep_span[1], merged))
        for f, span, _ in occ[1:]:
            edits.setdefault(f, []).append((span[0], span[1], None))
        sizes = ', '.join(f"{os.path.basename(os.path.dirname(os.path.dirname(f)))}:{len(a)}"
                          for f, _, a in occ)
        print(f"  styleable/{name}: {len(occ)} declarations ({sizes}) -> union of {len(union)} attrs")

    if dry:
        print("  (--dry-run: no files written)")
        return 0

    for f, spans in edits.items():
        s = open(f, errors='ignore').read()
        for start, end, repl in sorted(spans, key=lambda x: -x[0]):   # reverse: offsets stay valid
            s = s[:start] + (repl if repl is not None else '') + s[end:]
        open(f, 'w').write(s)
    print(f"  rewrote {len(edits)} values file(s)")
    return 0


if __name__ == '__main__':
    sys.exit(main())
