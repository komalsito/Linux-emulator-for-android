#!/usr/bin/env python3
"""Download every artifact in deps.lock and lay them out the way the rest of the
pipeline expects:

  $WORK/aars/<artifact>-<version>.<kind>     as downloaded
  $WORK/apkb/libs/<artifact>-<version>.jar   classes.jar (or the jar itself)
  $WORK/apkb/libs/*.jar                      ALSO any libs/*.jar inside an AAR -
                                             emoji2-1.3.0.aar ships its repackaged
                                             guava-retrying as libs/repackaged.jar,
                                             which is a runtime class path entry
                                             with no Maven coordinate of its own
  $WORK/apkb/libs/<name>.R.txt               the AAR's symbol table (drives gen_r.py)
  $WORK/apkb/res/<name>/                     the AAR's res/ (drives aapt2 compile)

Only `curl`-free stdlib is used, because the sandbox this was developed in has no
curl. Nothing is cached across runs; re-running re-downloads (idempotent).
"""
import os, re, sys, io, shutil, zipfile, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
WORK = os.environ.get('WORK', '/tmp/emux-nogradle')
APKB = os.path.join(WORK, 'apkb')
AARS = os.path.join(WORK, 'aars')
LOCK = os.environ.get('DEPS_LOCK', os.path.join(HERE, 'deps.lock'))
REPOS = {'google': 'https://dl.google.com/dl/android/maven2',
         'central': 'https://repo1.maven.org/maven2'}


def get(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'curl/8'})
    return urllib.request.urlopen(req, timeout=300).read()


def main():
    os.makedirs(APKB + '/libs', exist_ok=True)
    os.makedirs(APKB + '/res', exist_ok=True)
    os.makedirs(AARS, exist_ok=True)
    n = 0
    for ln in open(LOCK):
        ln = ln.split('#')[0].strip() if not ln.startswith('#') else ''
        m = re.match(r'^([\w.\-]+):([\w.\-]+):([\w.\-]+)@(aar|jar)$', ln)
        if not m:
            continue
        g, a, v, kind = m.groups()
        name = '%s-%s' % (a, v)
        url = '%s/%s/%s/%s/%s-%s.%s' % (REPOS['google'], g.replace('.', '/'), a, v, a, v, kind)
        try:
            raw = get(url)
        except Exception:
            raw = get(url.replace(REPOS['google'], REPOS['central']))
        open(os.path.join(AARS, name + '.' + kind), 'wb').write(raw)
        z = zipfile.ZipFile(io.BytesIO(raw))
        if kind == 'jar':
            open(os.path.join(APKB, 'libs', name + '.jar'), 'wb').write(raw)
        else:
            if 'classes.jar' in z.namelist():
                open(os.path.join(APKB, 'libs', name + '.jar'), 'wb').write(z.read('classes.jar'))
            for inner in z.namelist():            # AAR-embedded runtime jars
                if re.match(r'^libs/[^/]+\.jar$', inner):
                    stem = re.sub(r'\.jar$', '', inner.split('/')[1])
                    open(os.path.join(APKB, 'libs', '%s-%s.jar' % (stem, v)), 'wb').write(z.read(inner))
            open(os.path.join(APKB, 'libs', name + '.R.txt'), 'w').write(
                z.read('R.txt').decode() if 'R.txt' in z.namelist() else '')
            d = os.path.join(APKB, 'res', name)
            if os.path.isdir(d):
                shutil.rmtree(d)
            for x in z.namelist():
                if x.startswith('res/') and not x.endswith('/'):
                    t = os.path.join(APKB, x.replace('res/', d + '/', 1))
                    os.makedirs(os.path.dirname(t), exist_ok=True)
                    open(t, 'wb').write(z.read(x))
        n += 1
        print('  %-52s %s' % (g + ':' + a + ':' + v, kind))
    print('fetched %d artifacts -> %s' % (n, APKB))


if __name__ == '__main__':
    main()
