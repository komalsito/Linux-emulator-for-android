#!/usr/bin/env python3
"""Assemble the final APK: aapt2's linked base + dex + the merged java resources.

Why this is a script and not a `zip` one-liner: three details decide whether the
result installs and runs.

1. Compression per entry must be preserved. `resources.arsc` has to stay STORED
   (Android mmap's it; a deflated arsc makes the package installer reject the APK
   on API 30+), while classes*.dex must be DEFLATED the way AGP stores them -
   storing them raw turns a 7 MB APK into a 14 MB one.

2. AGP merges *every* non-class entry of every dependency jar into the APK, not
   just META-INF/services. Two of those matter at runtime:
     META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory
        - without it Dispatchers.Main throws "Module with the Main dispatcher is
          missing" on first use, i.e. the app dies as soon as it touches a
          lifecycleScope; and
     DebugProbesKt.bin, kotlin/*.kotlin_builtins - coroutine debugger / reflection
       payloads that are loaded by resource name, so a missing entry is a runtime
       failure, not a warning.
   AGP's default excludes are applied here too (signatures, MANIFEST.MF,
   multi-release META-INF/versions/**, module-info, *.kotlin_module), because
   shipping a dependency's META-INF/*.SF would invalidate our own signature.

3. zipalign runs BEFORE apksigner, never after: the v2 signature covers the file
   bytes, so re-aligning a signed APK breaks the signature.
"""


import argparse, os, re, sys, zipfile, glob, hashlib

EXCLUDE_RE = re.compile(
    r'(^META-INF/MANIFEST\.MF$'
    r'|^META-INF/[^/]+\.(SF|DSA|RSA|EC)$'
    r'|^META-INF/versions/'
    r'|(^|/)module-info\.class$'
    r'|\.kotlin_module$'
    r'|^META-INF/(LICENSE|NOTICE|DEPENDENCIES)'
    # AGP *consumes* these (feeds them to R8) instead of packaging them; a debug
    # build with minifyEnabled=false never reads them, and the reference APK does
    # not contain them.
    r'|^META-INF/maven/'
    r'|^META-INF/proguard/'
    r'|^META-INF/com\.android\.tools/)', re.I)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--base', required=True, help='aapt2 link output (resources + assets + manifest)')
    ap.add_argument('--dex-dir', required=True, help='directory holding classes*.dex from d8')
    ap.add_argument('--libs', required=True, help='directory of dependency jars to harvest resources from')
    ap.add_argument('--out', required=True)
    ap.add_argument('--app-metadata', help='optional file to store as META-INF/com/android/build/gradle/app-metadata.properties')
    a = ap.parse_args()

    out = zipfile.ZipFile(a.out, 'w')
    base = zipfile.ZipFile(a.base)
    n_base = 0
    for i in base.infolist():                       # preserve the linker's choices exactly
        zi = zipfile.ZipInfo(i.filename, date_time=i.date_time)
        zi.compress_type = i.compress_type
        zi.external_attr = i.external_attr
        out.writestr(zi, base.read(i.filename))
        n_base += 1

    dex = sorted(glob.glob(os.path.join(a.dex_dir, 'classes*.dex')))
    if not dex:
        sys.exit('no classes*.dex in %s' % a.dex_dir)
    for f in dex:
        zi = zipfile.ZipInfo(os.path.basename(f), date_time=(1980, 1, 1, 0, 0, 0))
        zi.compress_type = zipfile.ZIP_DEFLATED
        out.writestr(zi, open(f, 'rb').read())

    seen, harvested, conflicts = set(), 0, []
    for j in sorted(glob.glob(os.path.join(a.libs, '*.jar'))):
        z = zipfile.ZipFile(j)
        for i in z.infolist():
            nm = i.filename
            if i.is_dir() or nm.endswith('.class') or EXCLUDE_RE.search(nm):
                continue
            if nm in seen:                          # AGP's "pick first" merge rule
                conflicts.append(nm)
                continue
            seen.add(nm)
            zi = zipfile.ZipInfo(nm, date_time=(1980, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_STORED if nm.startswith('META-INF/') and nm.endswith('.version') \
                else zipfile.ZIP_DEFLATED
            out.writestr(zi, z.read(nm))
            harvested += 1
    if a.app_metadata and os.path.exists(a.app_metadata):
        zi = zipfile.ZipInfo('META-INF/com/android/build/gradle/app-metadata.properties',
                             date_time=(1980, 1, 1, 0, 0, 0))
        zi.compress_type = zipfile.ZIP_DEFLATED
        out.writestr(zi, open(a.app_metadata, 'rb').read())
        harvested += 1
    out.close()
    print('packaged %s: %d base entries + %d dex + %d harvested resources = %d bytes'
          % (a.out, n_base, len(dex), harvested, os.path.getsize(a.out)))
    if conflicts:
        print('  note: %d duplicate resource path(s) resolved first-wins: %s'
              % (len(conflicts), ', '.join(sorted(set(conflicts))[:4])))


if __name__ == '__main__':
    main()
#!/usr/bin/env python3
"""Assemble the final APK: aapt2's linked base + dex + the merged java resources.

Why this is a script and not a `zip` one-liner: three details decide whether the
result installs and runs.

1. Compression per entry must be preserved. `resources.arsc` has to stay STORED
   (Android mmap's it; a deflated arsc makes the package installer reject the APK
   on API 30+), while classes*.dex must be DEFLATED the way AGP stores them -
   storing them raw turns a 7 MB APK into a 14 MB one.

2. AGP merges *every* non-class entry of every dependency jar into the APK, not
   just META-INF/services. Two of those matter at runtime:
     META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory
        - without it Dispatchers.Main throws "Module with the Main dispatcher is
          missing" on first use, i.e. the app dies as soon as it touches a
          lifecycleScope; and
     DebugProbesKt.bin, kotlin/*.kotlin_builtins - coroutine debugger / reflection
       payloads that are loaded by resource name, so a missing entry is a runtime
       failure, not a warning.
   AGP's default excludes are applied here too (signatures, MANIFEST.MF,
   multi-release META-INF/versions/**, module-info, *.kotlin_module), because
   shipping a dependency's META-INF/*.SF would invalidate our own signature.

3. zipalign runs BEFORE apksigner, never after: the v2 signature covers the file
   bytes, so re-aligning a signed APK breaks the signature.
"""
import argparse, os, re, sys, zipfile, glob, hashlib

EXCLUDE_RE = re.compile(
    r'(^META-INF/MANIFEST\.MF$'
    r'|^META-INF/[^/]+\.(SF|DSA|RSA|EC)$'
    r'|^META-INF/versions/'
    r'|(^|/)module-info\.class$'
    r'|\.kotlin_module$'
    r'|^META-INF/(LICENSE|NOTICE|DEPENDENCIES)'
    # AGP *consumes* these (feeds them to R8) instead of packaging them; a debug
    # build with minifyEnabled=false never reads them, and the reference APK does
    # not contain them.
    r'|^META-INF/maven/'
    r'|^META-INF/proguard/'
    r'|^META-INF/com\.android\.tools/)', re.I)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--base', required=True, help='aapt2 link output (resources + assets + manifest)')
    ap.add_argument('--dex-dir', required=True, help='directory holding classes*.dex from d8')
    ap.add_argument('--libs', required=True, help='directory of dependency jars to harvest resources from')
    ap.add_argument('--out', required=True)
    ap.add_argument('--app-metadata', help='optional file to store as META-INF/com/android/build/gradle/app-metadata.properties')
    a = ap.parse_args()

    out = zipfile.ZipFile(a.out, 'w')
    base = zipfile.ZipFile(a.base)
    n_base = 0
    for i in base.infolist():                       # preserve the linker's choices exactly
        zi = zipfile.ZipInfo(i.filename, date_time=i.date_time)
        zi.compress_type = i.compress_type
        zi.external_attr = i.external_attr
        out.writestr(zi, base.read(i.filename))
        n_base += 1

    dex = sorted(glob.glob(os.path.join(a.dex_dir, 'classes*.dex')))
    if not dex:
        sys.exit('no classes*.dex in %s' % a.dex_dir)
    for f in dex:
        zi = zipfile.ZipInfo(os.path.basename(f), date_time=(1980, 1, 1, 0, 0, 0))
        zi.compress_type = zipfile.ZIP_DEFLATED
        out.writestr(zi, open(f, 'rb').read())

    seen, harvested, conflicts = set(), 0, []
    for j in sorted(glob.glob(os.path.join(a.libs, '*.jar'))):
        z = zipfile.ZipFile(j)
        for i in z.infolist():
            nm = i.filename
            if i.is_dir() or nm.endswith('.class') or EXCLUDE_RE.search(nm):
                continue
            if nm in seen:                          # AGP's "pick first" merge rule
                conflicts.append(nm)
                continue
            seen.add(nm)
            zi = zipfile.ZipInfo(nm, date_time=(1980, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_STORED if nm.startswith('META-INF/') and nm.endswith('.version') \
                else zipfile.ZIP_DEFLATED
            out.writestr(zi, z.read(nm))
            harvested += 1
    if a.app_metadata and os.path.exists(a.app_metadata):
        zi = zipfile.ZipInfo('META-INF/com/android/build/gradle/app-metadata.properties',
                             date_time=(1980, 1, 1, 0, 0, 0))
        zi.compress_type = zipfile.ZIP_DEFLATED
        out.writestr(zi, open(a.app_metadata, 'rb').read())
        harvested += 1
    out.close()
    print('packaged %s: %d base entries + %d dex + %d harvested resources = %d bytes'
          % (a.out, n_base, len(dex), harvested, os.path.getsize(a.out)))
    if conflicts:
        print('  note: %d duplicate resource path(s) resolved first-wins: %s'
              % (len(conflicts), ', '.join(sorted(set(conflicts))[:4])))


if __name__ == '__main__':
    main()
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