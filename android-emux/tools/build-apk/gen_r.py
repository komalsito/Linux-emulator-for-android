"""Generate the per-library R classes that Gradle would emit, because
android.nonTransitiveRClass=true means each library reads its OWN R at runtime
(androidx.appcompat.R.styleable.SearchView and com.google.android.material.R.styleable
.SearchView are different arrays). aapt2 link only ever produces one R for the app
package, so without this the libraries hit NoClassDefFoundError on first inflate.

Inputs:  the final R.txt from `aapt2 link`  (authoritative resource IDs)
         each AAR's own R.txt               (authoritative per-library field sets)
         android.jar's android.R$attr       (framework attr IDs for android:* styleable entries)
Styleable arrays are rebuilt by NAME (index entries -> attr name -> final ID) rather than
copied, because an AAR's R.txt carries the IDs from ITS build, not ours.
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
import zipfile, re, os, sys, subprocess, collections, json

# ---------- 1. final resource table ----------
final={}                 # (type,name) -> '0x7f0400ab'
final_styleable={}       # name -> [ids]
for ln in open(os.path.join(APKB,'R.txt')):
    ln=ln.rstrip('\n')
    m=re.match(r'^int (\S+) (\S+) (0x[0-9a-f]+)$',ln)
    if m: final[(m.group(1),m.group(2))]=m.group(3); continue
    m=re.match(r'^int\[\] styleable (\S+) \{(.*)\}$',ln)
    if m: final_styleable[m.group(1)]=[x.strip() for x in m.group(2).split(',') if x.strip()]
print('final table: %d resources, %d styleable arrays'%(len(final),len(final_styleable)))

# ---------- 2. framework attr ids ----------
fw={}
out=subprocess.run(['javap','-classpath',ANDROID_JAR,'-constants','android.R$attr'],
                   capture_output=True,text=True).stdout
for m in re.finditer(r'static final int (\w+) = (-?\d+);',out):
    fw[m.group(1)]='0x%08x'%(int(m.group(2))&0xffffffff)
print('framework attrs from android.jar: %d'%len(fw))
# id -> attr name, for both the app/library space and the framework space. Gradle derives
# styleable index constants from the FINAL array (that is why the oracle's constraintlayout R
# carries appcompat 1.7.0's new AppCompatTheme_actionModeTheme, which constraintlayout 2.0.1's
# own R.txt has never heard of), so we need to name each id in that array.
rev_final={}
for (t,n),v in final.items():
    if t=='attr': rev_final.setdefault(v,n)
rev_fw={}
for n,v in fw.items(): rev_fw.setdefault(v,n)
def attr_name_of(rid):
    if rid in rev_final: return rev_final[rid]
    if rid in rev_fw: return 'android_'+rev_fw[rid]
    return None

def lookup_id(typ,name,pkg,miss):
    v=final.get((typ,name))
    if v is None: miss.append((pkg,typ,name))
    return v

def styleable_array(pkg,name,idx,miss):
    """Build int[] for styleable `name` from its index entries: idx maps attrName->position."""
    ids=[None]*len(idx)
    for attr,i in idx.items():
        if attr.startswith('android_'):
            v=fw.get(attr[len('android_'):])
            if v is None: miss.append((pkg,'fw-attr',attr))
        else:
            v=lookup_id('attr',attr,pkg,miss)
        if v is None: return None
        if i>=len(ids): return None
        ids[i]=v
    return ids

# ---------- 3. per-AAR R.txt, grouped by namespace ----------
by_pkg=collections.defaultdict(list)   # pkg -> list of R.txt text
aar_pkg={}
for f in sorted(os.listdir(AARS)):
    if not f.endswith('.aar'): continue
    z=zipfile.ZipFile(os.path.join(AARS,f))
    mf=z.read('AndroidManifest.xml').decode('utf-8','replace')
    m=re.search(r'package\s*=\s*"([^"]+)"',mf)
    if not m: continue
    pkg=m.group(1); aar_pkg[f[:-4]]=pkg
    if 'R.txt' in z.namelist():
        by_pkg[pkg].append((f,z.read('R.txt').decode('utf-8','replace')))

missing=[]; report={}
for pkg,sources in sorted(by_pkg.items()):
    scalars={}          # (type,name) -> value string
    fromfinal=rebuilt=0
    st_idx=collections.defaultdict(dict)   # styleable name -> {attr:index}
    st_names=[]
    # Pass 1: the authoritative styleable NAMES come from the `int[] styleable X {` lines.
    # Splitting an index entry on its LAST underscore is wrong - `LinearLayoutCompat_Layout_
    # android_layout_gravity` would yield name=...android_layout, attr=gravity - so the name
    # must be matched against this list and only then stripped.
    raw=[]
    for fname,rt in sources:
        for ln in rt.splitlines():
            m=re.match(r'^int\[\] styleable (\S+) \{',ln)
            if m:
                if m.group(1) not in st_names: st_names.append(m.group(1))
                continue
            raw.append(ln)
    st_names.sort(key=len,reverse=True)      # longest first: Layout before ..._Layout
    for ln in raw:
        m=re.match(r'^int styleable (\S+) (\d+)$',ln)
        if m:
            rest,idxs=m.group(1),int(m.group(2))
            nm=next((n for n in st_names if rest.startswith(n+'_')),None)
            if nm is None: missing.append((pkg,'styleable-orphan',rest)); continue
            st_idx[nm][rest[len(nm)+1:]]=idxs
            continue
        m=re.match(r'^int (\S+) (\S+) (\S+)$',ln)
        if m:
            t,n=m.group(1),m.group(2)
            if t=='styleable': continue
            v=lookup_id(t,n,pkg,missing)
            if v is not None: scalars[(t,n)]=v
            continue
    # emit
    path=pkg.replace('.','/')
    d=os.path.join(APKB,'genR',path); os.makedirs(d,exist_ok=True)
    by_type=collections.defaultdict(list)
    for (t,n),v in scalars.items(): by_type[t].append((n,v))
    L=['/* GENERATED for the no-Gradle build: %s.R (non-transitive, %d source AAR(s)). */'%(pkg,len(sources)),
       'package %s;'%pkg,'','public final class R {']
    nfields=0; narrays=0; checked=0; mismatch=[]
    for t in sorted(by_type):
        L.append('    public static final class %s {'%t)
        for n,v in sorted(by_type[t]):
            L.append('        public static final int %s = %s;'%(n,v)); nfields+=1
        L.append('    }')
    if st_names:
        L.append('    public static final class styleable {')
        for nm in sorted(st_names):
            idx=st_idx.get(nm,{})
            ref=final_styleable.get(nm)
            if ref is not None:
                # aapt2 linked this declaration: use ITS array (sorted by final id) and derive
                # the index constant of every attr in it from its position. This is what AGP
                # emits, and it is self-consistent by construction.
                arr=ref; pairs=[]
                for i,rid in enumerate(arr):
                    a=attr_name_of(rid)
                    if a is None: pairs=None; break
                    pairs.append((a,i))
                if pairs is None: continue
                fromfinal+=1
            else:
                # Not in the linked table (its <declare-styleable> lost a cross-library
                # collision, or its library contributed no values). Rebuild by name from this
                # library's own R.txt index entries.
                arr=styleable_array(pkg,nm,idx,missing) if idx else None
                if arr is None: continue
                pairs=sorted(idx.items(),key=lambda kv:kv[1])
                rebuilt+=1
            L.append('            public static final int[] %s = {%s};'%(nm,','.join(' '+a for a in arr)))
            for a,i in pairs:
                L.append('            public static final int %s_%s = %d;'%(nm,a,i))
            narrays+=1; nfields+=len(pairs)
            if ref is not None: checked+=1
        L.append('        }')
    L.append('}')
    open(os.path.join(d,'R.java'),'w').write('\n'.join(L)+'\n')
    report[pkg]={'fields':nfields,'arrays':narrays,'from_final':fromfinal,'rebuilt':rebuilt,
                 'sources':[s[0] for s in sources]}

print('\n=== generated %d R classes ==='%len(report))
for pkg,r in sorted(report.items()):
    print('  %-42s fields=%-6d styleables=%-4d (from-final=%-4d rebuilt=%d)'%(pkg,r['fields'],r['arrays'],r['from_final'],r['rebuilt']))
print('\nunresolved references (skipped): %d'%len(missing))
cnt=collections.Counter((p,t) for p,t,n in missing)
for (p,t),c in cnt.most_common(15): print('   %-40s %-12s %d'%(p,t,c))
json.dump({'report':report,'missing':missing},open(os.path.join(WORK,'gen_r_report.json'),'w'),indent=1)