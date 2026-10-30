"""Generate the ViewBinding class that AGP would, straight from the layout XML, so the
binding can never drift from the layout. The compile-time stub in tools/ is only for
type-checking; this is the real thing that goes into the dex."""


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
import re,os,sys
import xml.etree.ElementTree as ET
REPO=sys.argv[1]; LAYOUT=sys.argv[2]; OUT=sys.argv[3]; CLS=sys.argv[4]
AND={'LinearLayout':'android.widget.LinearLayout','TextView':'android.widget.TextView',
     'Button':'android.widget.Button','FrameLayout':'android.widget.FrameLayout',
     'ImageView':'android.widget.ImageView','EditText':'android.widget.EditText',
     'ProgressBar':'android.widget.ProgressBar','ScrollView':'android.widget.ScrollView',
     'RelativeLayout':'android.widget.RelativeLayout','View':'android.view.View',
     'ViewGroup':'android.view.ViewGroup','Space':'android.widget.Space',
     'CheckBox':'android.widget.CheckBox','Switch':'android.widget.Switch',
     'ImageButton':'android.widget.ImageButton','SeekBar':'android.widget.SeekBar',
     'RadioButton':'android.widget.RadioButton','RadioGroup':'android.widget.RadioGroup',
     'Toolbar':'android.widget.Toolbar','Spinner':'android.widget.Spinner',
     'WebView':'android.webkit.WebView','SurfaceView':'android.view.SurfaceView',
     'TextureView':'android.view.TextureView','ViewStub':'android.view.ViewStub'}
def fqcn(tag):
    if '.' in tag: return tag
    return AND.get(tag,'android.widget.'+tag)
def camel(s):
    if '_' not in s: return s
    p=s.split('_'); return p[0]+''.join(x[:1].upper()+x[1:] for x in p[1:])

tree=ET.parse(LAYOUT); root=tree.getroot()
assert root.tag not in ('merge','include'), 'root <%s> needs a different binding shape'%root.tag
rootcls=fqcn(root.tag)
fields=[]
for el in root.iter():
    if el is root: continue
    aid=el.get('{http://schemas.android.com/apk/res/android}id')
    if not aid or not aid.startswith('@+id/'): continue
    nm=camel(aid[5:]); c=fqcn(el.tag)
    fields.append((nm,c))
    if el.tag=='include': raise SystemExit('<include> needs a nested binding - not handled')
imports=sorted({c for _,c in fields}|{rootcls})
L=['/* GENERATED for the no-Gradle build from %s - mirrors what AGP\'s viewBinding emits. */'%os.path.basename(LAYOUT),
   'package com.emux.databinding;','','import android.view.LayoutInflater;','import android.view.View;',
   'import android.view.ViewGroup;','import androidx.annotation.NonNull;','import androidx.annotation.Nullable;',
   'import androidx.viewbinding.ViewBinding;','import androidx.viewbinding.ViewBindings;','import com.emux.R;']
for i in imports: L.append('import %s;'%i)
L+= ['',
 'public final class %s implements ViewBinding {'%CLS,
 '    @NonNull private final %s rootView;'%rootcls.split('.')[-1]]
for nm,c in fields: L.append('    @NonNull public final %s %s;'%(c.split('.')[-1],nm))
L.append('')
L.append('    private %s(@NonNull %s rootView%s) {'%(CLS,rootcls.split('.')[-1],
         ''.join(', @NonNull %s %s'%(c.split('.')[-1],nm) for nm,c in fields)))
L.append('        this.rootView = rootView;')
for nm,_ in fields: L.append('        this.%s = %s;'%(nm,nm))
L+= ['    }','','    @Override','    @NonNull','    public %s getRoot() {'%rootcls.split('.')[-1],
     '        return rootView;','    }','',
     '    @NonNull','    public static %s inflate(@NonNull LayoutInflater inflater) {'%CLS,
     '        return inflate(inflater, null, false);','    }','',
     '    @NonNull','    public static %s inflate(@NonNull LayoutInflater inflater,'%CLS,
     '            @Nullable ViewGroup parent, boolean attachToParent) {',
     '        View root = inflater.inflate(R.layout.activity_main, parent, false);',
     '        if (attachToParent) {','            parent.addView(root);','        }',
     '        return bind(root);','    }','',
     '    @NonNull','    public static %s bind(@NonNull View rootView) {'%CLS]
for nm,c in fields:
    L+= ['        %s %s = ViewBindings.findChildViewById(rootView, R.id.%s);'%(c.split('.')[-1],nm,nm),
         '        if (%s == null) {'%nm,
         '            throw new NullPointerException("Missing required view with ID: %s");'%nm,
         '        }']
L.append('        return new %s((%s) rootView%s);'%(CLS,rootcls.split('.')[-1],
         ''.join(', %s'%nm for nm,_ in fields)))
L+=['    }','}']
os.makedirs(OUT,exist_ok=True)
open(os.path.join(OUT,'%s.java'%CLS),'w').write('\n'.join(L)+'\n')
print('%s: root=%s, %d bound views: %s'%(CLS,rootcls,len(fields),', '.join(n for n,_ in fields)))