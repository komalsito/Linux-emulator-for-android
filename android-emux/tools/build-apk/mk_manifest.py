#!/usr/bin/env python3
"""Convert `aapt2 dump xmltree` of the ORACLE APK's merged AndroidManifest.xml
back into XML source, so a hand-built APK gets exactly the manifest Gradle's
merger produced (library components included) instead of an approximation.

Resource references are resolved to SYMBOLIC names (@style/EmuX) via the
oracle's own resource table: a fresh aapt2 link reassigns IDs, so a numeric
reference copied from the oracle would point at the wrong resource.
"""

import os as _os
HERE = _os.path.dirname(_os.path.abspath(__file__))
# Everything is overridable; the defaults are a plain checkout plus a scratch WORK dir.
REPO   = _os.environ.get('EMUX_REPO', _os.path.abspath(_os.path.join(HERE, '..', '..')))
WORK   = _os.environ.get('WORK', '/tmp/emux-nogradle')
APKB   = _os.path.join(WORK, 'apkb')          # flats/, libs/, res/, genR/, base.apk
AARS   = _os.path.join(WORK, 'aars')          # downloaded .aar/.jar, as fetched
OUT    = _os.path.join(WORK, 'out')           # classes, dex, unsigned/aligned/signed apk
ANDROID_JAR = _os.environ.get('ANDROID_JAR', '/opt/asdk/android-34/android.jar')
BUILD_TOOLS = _os.environ.get('BUILD_TOOLS', '/opt/bt/android-14')
R8_JAR      = _os.environ.get('R8_JAR', '/opt/r8-8.5.35.jar')
STUBS       = _os.environ.get('STUBS', '/tmp/stubs')
ORACLE_APK  = _os.environ.get('ORACLE_APK', _os.path.join(_os.path.dirname(REPO), 'emux-debug.apk'))
ORACLE_MANIFEST = _os.environ.get('ORACLE_MANIFEST', _os.path.join(WORK, 'oracle_manifest.txt'))
ORACLE_RES      = _os.environ.get('ORACLE_RES', _os.path.join(WORK, 'oracle_res.txt'))
ORACLE_DEFINED  = _os.environ.get('ORACLE_DEFINED', _os.path.join(WORK, 'oracle_defined.txt'))
MERGED_MANIFEST = _os.environ.get('MERGED_MANIFEST', _os.path.join(WORK, 'merged_manifest.xml'))

import os, re, sys

idmap = {}
for line in open(ORACLE_RES):
    m = re.match(r'\s+resource (0x[0-9a-f]+) (\S+)', line)
    if m:
        idmap[m.group(1)] = m.group(2)

GRADLE_KTS = os.environ.get('GRADLE_KTS',
    REPO+'/app/build.gradle.kts')
ANDROID_NS = 'http://schemas.android.com/apk/res/android'

def conv(v):
    v = v.strip()
    if v.startswith('@0x'):
        rid = v.split()[0][1:]   # drop the '@'
        if rid not in idmap:
            sys.exit('UNMAPPED RESOURCE REF: ' + rid)
        return '@' + idmap[rid]
    m = re.match(r'^"(.*)" \(Raw: "(.*)"\)$', v, re.S)
    if m:
        return m.group(2)
    m = re.match(r'^"(.*)"$', v, re.S)
    if m:
        return m.group(1)
    return v  # int / true / false

# Values the oracle's binary manifest stores as raw flag ints. aapt2 parses hex
# literals, but the symbolic form is what Gradle's merger started from and what
# a human reading the file expects.
# Attributes whose value is an ENUM (not a bitmask): the binary manifest stores the
# ordinal, but aapt2 link requires the symbolic name, e.g. launchMode="2" is rejected
# while launchMode="singleTask" is accepted. The value sets below are the ones aapt2
# itself prints in its error message, so they cannot drift from the platform.
ENUM_NAMES = {
    'launchMode': {0: 'standard', 1: 'singleTop', 2: 'singleTask', 3: 'singleInstance',
                   4: 'singleInstancePerTask'},
}

FLAG_NAMES = {
    'configChanges': {0x1fb0: 'orientation|screenSize|keyboardHidden|keyboard|'
                              'screenLayout|smallestScreenSize|density|uiMode'},
    'windowSoftInputMode': {0x10: 'adjustResize'},
    'foregroundServiceType': {0x40000000: 'specialUse'},
    'protectionLevel': {0x2: 'signature'},
}

def esc(s):
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')

root = None
stack = []          # list of nodes: {'name':..,'attrs':[],'children':[]}
for ln in open(ORACLE_MANIFEST):
    ln = ln.rstrip('\n')
    if ln.startswith('N: ') or not ln.strip():
        continue
    me = re.match(r'^(\s*)E: (\S+)', ln)
    ma = re.match(r'^(\s*)A: (.+)$', ln)
    if me:
        # The dump indents FOUR spaces per level, elements starting at column 2:
        #   '  E: manifest' -> 0, '      E: uses-sdk' -> 1, '          E: package'
        #   (inside <queries>) -> 2.
        # Pop back to this depth first - without that, every element becomes a
        # child of the one before it and the output is one unclosed nesting
        # (aapt2: "unexpected element <uses-permission> found in
        # <manifest><uses-sdk>").
        d = (len(me.group(1)) - 2) // 4
        while len(stack) > d:
            stack.pop()
        node = {'name': me.group(2), 'attrs': [], 'children': [], 'depth': d}
        if not stack:
            root = node
        else:
            stack[-1]['children'].append(node)
        stack.append(node)
        continue
    if ma:
        if not stack:
            sys.exit('attribute before any element: ' + ln)
        body = ma.group(2)
        m2 = re.match(r'^(\S+):([A-Za-z0-9_]+)\(0x[0-9a-f]+\)=(.*)$', body)
        if m2:
            if m2.group(1) != ANDROID_NS:
                sys.exit('UNKNOWN NS: ' + m2.group(1))
            attr = 'android:' + m2.group(2)
            v = m2.group(3)
        else:
            m3 = re.match(r'^([A-Za-z0-9_.]+)=(.*)$', body)
            if not m3:
                sys.exit('UNPARSED ATTR: ' + body)
            attr, v = m3.group(1), m3.group(2)
        cv = conv(v)
        nm = attr.split(':', 1)[1] if attr.startswith('android:') else attr
        # Enum attributes: aapt2 prints the ordinal in DECIMAL (launchMode=2) and
        # `link` refuses the ordinal, wanting the symbolic name (singleTask).
        if nm in ENUM_NAMES and re.match(r'^-?\d+$', cv):
            sym = ENUM_NAMES[nm].get(int(cv))
            if sym: cv = sym
        # Flag attributes: printed as a hex mask; emit the symbolic OR-list.
        m4 = re.match(r'^0x([0-9a-f]+)$', cv)
        if m4 and attr.startswith('android:'):
            sym = FLAG_NAMES.get(nm, {}).get(int(m4.group(1), 16))
            if sym: cv = sym
        stack[-1]['attrs'].append((attr, cv))
        continue
    sys.exit('UNPARSED LINE: ' + ln)

# aapt2 supplies these itself; keeping the oracle's values would just be noise.
DROP = {'android:compileSdkVersion', 'android:compileSdkVersionCodename',
        'platformBuildVersionCode', 'platformBuildVersionName'}

def emit(node, depth, out):
    pad = '    ' * depth
    parts = [pad + '<' + node['name']]
    if depth == 0:
        parts.append('    xmlns:android="%s"' % ANDROID_NS)
    for a, v in node['attrs']:
        if a in DROP:
            continue
        parts.append('    %s="%s"' % (a, esc(v)))
    head = ' '.join(p.strip() for p in parts)
    if not node['children']:
        out.append(head + ' />')
        return
    out.append(head + '>')
    for c in node['children']:
        emit(c, depth + 1, out)
    out.append(pad + '</%s>' % node['name'])

out = []
emit(root, 0, out)
body = '\n'.join(out)
# one attribute per line is noisy; collapse the element head onto one line
body = re.sub(r'<(\S+)\s+', r'<\1 ', body)
# android:versionCode/versionName must come from app/build.gradle.kts, not from the oracle
# APK this manifest was reconstructed from - aapt2 link's --version-code/--version-name do NOT
# override values already present in the manifest, so a stale manifest silently ships a stale
# version (and `adb install -r` then refuses to downgrade).
import re as _re
_gradle = _re.search(r'versionCode\s*=\s*(\d+)', open(GRADLE_KTS).read())
_gname  = _re.search(r'versionName\s*=\s*"([^"]+)"', open(GRADLE_KTS).read())
if _gradle:
    body = _re.sub(r'android:versionCode="\d+"', 'android:versionCode="%s"' % _gradle.group(1), body, count=1)
if _gname:
    body = _re.sub(r'android:versionName="[^"]+"', 'android:versionName="%s"' % _gname.group(1), body, count=1)
sys.stderr.write('version: code=%s name=%s (from %s)\n' % (
    _gradle.group(1) if _gradle else '?', _gname.group(1) if _gname else '?', GRADLE_KTS))
sys.stdout.write('<?xml version="1.0" encoding="utf-8"?>\n' + body + '\n')