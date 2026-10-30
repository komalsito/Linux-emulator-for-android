#!/usr/bin/env python3
"""Fail if any compiled class is not traceable to a real source file or a generated
one. The two-pass compile uses stubs for classes a later pass compiles for real; a
stub that survives into the dex would replace real behaviour with a
UnsupportedOperationException-throwing shell, and nothing else in the pipeline would
notice - the APK would still verify as byte-correct."""
import os, re, sys, glob
merged = sys.argv[1]
repo = os.environ.get('EMUX_REPO') or os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
work = os.environ.get('WORK', '/tmp/emux-nogradle')
src = set()
for f in glob.glob(os.path.join(repo, 'app/src/main/java/**/*.kt'), recursive=True):
    pkg = None
    for ln in open(f, encoding='utf-8', errors='replace'):
        m = re.match(r'package (\S+)', ln)
        if m: pkg = m.group(1); continue
        m = re.match(r'(?:@\w+\s+)*(?:public |internal |private |open |abstract |sealed |data |annotation |value )*\(?(?:class|object|interface|enum class)\)?\s+(\w+)', ln)
        if m and pkg: src.add(pkg + '.' + m.group(1))
    # a file with top-level functions also yields a <FileName>Kt facade class
    base = os.path.basename(f)[:-3]
    if pkg: src.add(pkg + '.' + base + 'Kt')
gen = set()
for d in ('genR', 'genbind'):
    for f in glob.glob(os.path.join(work, 'apkb', d, '**/*.java'), recursive=True):
        m = re.search(r'package (\S+);', open(f).read())
        if m: gen.add(m.group(1) + '.' + os.path.basename(f)[:-5])
bad = []
for f in glob.glob(os.path.join(merged, '**/*.class'), recursive=True):
    cn = os.path.relpath(f, merged)[:-6].replace(os.sep, '.')
    top = re.sub(r'\$.*$', '', cn)
    if top not in src and top not in gen: bad.append(cn)
if bad:
    print('   STUB LEAK / UNTRACEABLE CLASSES (%d):' % len(bad))
    for b in sorted(bad)[:20]: print('      ', b)
    sys.exit(1)
print('   %d classes, all traceable' % len(glob.glob(os.path.join(merged, '**/*.class'), recursive=True)))
