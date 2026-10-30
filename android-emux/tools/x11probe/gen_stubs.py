#!/usr/bin/env python3
"""Generate com.emux.R and ActivityMainBinding stubs from the REAL resources,
so kotlinc can type-check the module without a full Android SDK/aapt2 build."""
import os, re, glob, xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.abspath(os.path.join(HERE, "..", ".."))     # android-emux/
ROOT = os.path.join(PROJ, "app", "src", "main")
OUT = os.environ.get("STUBS", "/tmp/stubs")
A = "{http://schemas.android.com/apk/res/android}"
os.makedirs(OUT, exist_ok=True)

# ---- R -------------------------------------------------------------------
vals = "".join(open(f).read() for f in glob.glob(f"{ROOT}/res/values/*.xml"))
groups = {}

def add(g, n):
    groups.setdefault(g, set()).add(n)

for m in re.finditer(r'<(string|plurals|color|dimen|integer|bool)\s+name="([^"]+)"', vals):
    add(m.group(1) if m.group(1) != "integer" else "integer", m.group(2))
for m in re.finditer(r'<string-array\s+name="([^"]+)"', vals):
    add("array", m.group(1))
for m in re.finditer(r'<style\s+name="([^"]+)"', vals):
    add("style", m.group(1).replace(".", "_"))
for f in glob.glob(f"{ROOT}/res/xml/*"):
    add("xml", os.path.basename(f).rsplit(".", 1)[0])
for d in ("drawable", "mipmap-anydpi-v26", "mipmap"):
    for f in glob.glob(f"{ROOT}/res/{d}/*"):
        add("drawable" if d.startswith("drawable") else "mipmap",
            os.path.basename(f).rsplit(".", 1)[0])
# ids referenced from layouts
for f in glob.glob(f"{ROOT}/res/layout/*.xml"):
    for m in re.finditer(r'android:id="@\+id/([A-Za-z0-9_]+)"', open(f).read()):
        add("id", m.group(1))
# ids referenced from Kotlin as R.id.X
for f in glob.glob(f"{ROOT}/java/**/*.kt", recursive=True):
    s = open(f).read()
    s = re.sub(r'//.*', '', s)                      # strip line comments
    s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)     # strip block comments
    for m in re.finditer(r'\bR\.([a-z]+)\.([A-Za-z0-9_]+)', s):
        add(m.group(1), m.group(2))

kind_of = {"string": "Int", "plurals": "Int", "style": "Int", "id": "Int",
           "xml": "Int", "drawable": "Int", "mipmap": "Int", "color": "Int",
           "dimen": "Int", "array": "Int", "integer": "Int", "bool": "Int",
           "layout": "Int", "raw": "Int", "menu": "Int", "anim": "Int"}

L = ["package com.emux", "", "/** Generated stub for kotlinc type-checking only. */",
     "object R {"]
for g in sorted(groups):
    t = kind_of.get(g, "Int")
    L.append(f"    object {g} {{")
    for n in sorted(groups[g]):
        if n.startswith("_"):
            continue
        L.append(f"        @JvmField val {n}: {t} = 0")
    L.append("    }")
L.append("}")
open(f"{OUT}/R.kt", "w").write("\n".join(L) + "\n")
print("R.kt:", {g: len(v) for g, v in sorted(groups.items())})

# The three hand-written stubs live in ./stubs and are copied next to the
# generated ones so compile_all.sh finds a complete set in $STUBS.
import shutil
for f in glob.glob(os.path.join(HERE, "stubs", "*.kt")):
    shutil.copy(f, OUT)
    print("copied", os.path.basename(f))

# ---- ViewBinding ---------------------------------------------------------
def parse_layout(path):
    tree = ET.parse(path)
    out = []
    for el in tree.iter():
        aid = el.get(A + "id")
        if aid and aid.startswith("@+id/"):
            name = aid[len("@+id/"):]
            cls = el.tag.split("}")[-1]
            out.append((name, cls))
    return out

MAP = {"TextView": "android.widget.TextView", "Button": "android.widget.Button",
       "LinearLayout": "android.widget.LinearLayout", "FrameLayout": "android.widget.FrameLayout",
       "RelativeLayout": "android.widget.RelativeLayout", "ImageView": "android.widget.ImageView",
       "View": "android.view.View", "ViewGroup": "android.view.ViewGroup",
       "ScrollView": "android.widget.ScrollView", "EditText": "android.widget.EditText",
       "ProgressBar": "android.widget.ProgressBar", "Space": "android.widget.Space",
       "HorizontalScrollView": "android.widget.HorizontalScrollView",
       "RecyclerView": "androidx.recyclerview.widget.RecyclerView",
       "CoordinatorLayout": "androidx.coordinatorlayout.widget.CoordinatorLayout",
       "ConstraintLayout": "androidx.constraintlayout.widget.ConstraintLayout",
       "ViewPager2": "androidx.viewpager2.widget.ViewPager2",
       "MaterialButton": "com.google.android.material.button.MaterialButton",
       "Toolbar": "androidx.appcompat.widget.Toolbar",
       "com.emux.x11.DesktopView": "com.emux.x11.DesktopView",
       "com.emux.terminal.ExtraKeysView": "com.emux.terminal.ExtraKeysView",
       "com.emux.terminal.VtTerminalView": "com.emux.terminal.VtTerminalView"}

for lp in glob.glob(f"{ROOT}/res/layout/*.xml"):
    base = os.path.basename(lp)[:-4]
    cls = "".join(seg.capitalize() for seg in base.split("_")) + "Binding"
    if cls != "ActivityMainBinding":
        continue
    ids = parse_layout(lp)
    root = ET.parse(lp).getroot()
    rootcls = MAP.get(root.tag.split("}")[-1], root.tag.split("}")[-1])
    L = ["package com.emux.databinding", "",
         "import android.view.LayoutInflater", "import android.view.View",
         "import android.view.ViewGroup", "",
         "/** Generated stub for kotlinc type-checking only. */",
         f"class {cls} private constructor(val root: {rootcls}) {{",
         "    companion object {",
         "        @JvmStatic fun inflate(inflater: LayoutInflater): " + cls +
         " = throw UnsupportedOperationException()",
         "        @JvmStatic fun inflate(inflater: LayoutInflater, parent: ViewGroup?, attach: Boolean): "
         + cls + " = throw UnsupportedOperationException()",
         "        @JvmStatic fun bind(v: View): " + cls + " = throw UnsupportedOperationException()",
         "    }"]
    for name, tag in ids:
        if name == "root":
            continue
        fq = MAP.get(tag, tag if "." in tag else "android.view.View")
        L.append(f"    lateinit var {name}: {fq}")
    L.append("}")
    open(f"{OUT}/{cls}.kt", "w").write("\n".join(L) + "\n")
    print(f"{cls}.kt: root={rootcls} fields={[n for n, _ in ids]}")
