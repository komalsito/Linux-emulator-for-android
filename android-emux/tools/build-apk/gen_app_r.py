"""Generate com.emux.R for the app module: NON-transitive, i.e. only the resources the app
itself declares - exactly what android.nonTransitiveRClass=true makes Gradle emit. IDs come
from the final linked table, so they agree with resources.arsc in the APK."""


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
import re,os,sys,glob
import xml.etree.ElementTree as ET
REPO=sys.argv[1] if len(sys.argv)>1 else REPO+''
RES=os.path.join(REPO,'app/src/main/res')
final={}
for ln in open(APKB+'/R.txt'):
    m=re.match(r'^int (\S+) (\S+) (0x[0-9a-f]+)$',ln.rstrip())
    if m: final[(m.group(1),m.group(2))]=m.group(3)

own=set()
# file-based resources: res/<type>[-qualifiers]/<name>.<ext>
for d in sorted(os.listdir(RES)):
    p=os.path.join(RES,d)
    if not os.path.isdir(p) or d.startswith('values'): continue
    typ=re.match(r'^([a-z]+)',d).group(1)
    for f in sorted(os.listdir(p)):
        n=re.sub(r'\.(9)?\.png$|\.xml$|\.jpg$|\.webp$|\.ttf$|\.otf$','',f)
        n=re.sub(r'\.[a-z0-9]+$','',n) if '.' in n else n
        own.add((typ,n))
# value-based resources
VTAG={'string':'string','plurals':'plurals','string-array':'array','integer-array':'array',
      'array':'array','color':'color','dimen':'dimen','bool':'bool','integer':'integer',
      'attr':'attr','declare-styleable':'styleable','style':'style','item':None}
for vf in glob.glob(os.path.join(RES,'values*','*.xml')):
    for ev,el in ET.iterparse(vf,events=('start',)):
        t=el.tag
        if t not in VTAG: continue
        if t=='item':
            ty=el.get('type'); nm=el.get('name')
            if ty and nm: own.add((ty,nm.replace('.','_') if ty=='style' else nm))
        elif t=='style':
            own.add(('style',el.get('name').replace('.','_')))
        elif t=='declare-styleable':
            own.add(('styleable',el.get('name')))
        else:
            own.add((VTAG[t],el.get('name')))
# @+id/ declared anywhere in the app's own XML
for xf in glob.glob(os.path.join(RES,'**','*.xml'),recursive=True):
    if '/values' in xf: continue
    for m in re.finditer(r'@\+id/([A-Za-z0-9_]+)',open(xf,encoding='utf-8',errors='replace').read()):
        own.add(('id',m.group(1)))

bytype={}
missing=[]
for t,n in sorted(own):
    v=final.get((t,n))
    if v is None: missing.append((t,n)); continue
    bytype.setdefault(t,[]).append((n,v))
os.makedirs(APKB+'/genR/com/emux',exist_ok=True)
L=['/* GENERATED for the no-Gradle build: com.emux.R, non-transitive (app-owned resources only). */',
   'package com.emux;','','public final class R {']
tot=0
for t in sorted(bytype):
    L.append('    public static final class %s {'%t)
    for n,v in sorted(bytype[t]):
        L.append('        public static final int %s = %s;'%(n,v)); tot+=1
    L.append('    }')
L.append('}')
open(APKB+'/genR/com/emux/R.java','w').write('\n'.join(L)+'\n')
print('com.emux.R: %d fields across %d types: %s'%(tot,len(bytype),{k:len(v) for k,v in sorted(bytype.items())}))
if missing: print('WARNING - app resources absent from the final table: %s'%missing)