#!/usr/bin/env python3
"""Fetch the jars/AARs needed to type-check the EmuX module with kotlinc."""
import os, sys, zipfile, urllib.request, shutil, concurrent.futures

OUT = "/opt/libs"
os.makedirs(OUT, exist_ok=True)
G = "https://dl.google.com/dl/android/maven2/{g}/{a}/{v}/{a}-{v}.{p}"
M = "https://repo1.maven.org/maven2/{g}/{a}/{v}/{a}-{v}.{p}"

ARTS = [
    ("org.jetbrains.kotlinx","kotlinx-coroutines-core-jvm","1.8.1","jar"),
    ("org.jetbrains.kotlinx","kotlinx-coroutines-android","1.8.1","jar"),
    ("org.tukaani","xz","1.10","jar"),
    ("androidx.annotation","annotation-jvm","1.8.0","jar"),
    ("androidx.annotation","annotation-experimental","1.4.0","aar"),
    ("androidx.arch.core","core-common","2.2.0","jar"),
    ("androidx.arch.core","core-runtime","2.2.0","aar"),
    ("androidx.collection","collection-jvm","1.4.0","jar"),
    ("androidx.collection","collection-ktx","1.4.0","aar"),
    ("androidx.lifecycle","lifecycle-common-jvm","2.8.6","jar"),
    ("androidx.lifecycle","lifecycle-runtime","2.8.6","aar"),
    ("androidx.lifecycle","lifecycle-runtime-ktx","2.8.6","aar"),
    ("androidx.lifecycle","lifecycle-livedata-core","2.8.6","aar"),
    ("androidx.lifecycle","lifecycle-livedata","2.8.6","aar"),
    ("androidx.lifecycle","lifecycle-viewmodel","2.8.6","aar"),
    ("androidx.lifecycle","lifecycle-viewmodel-ktx","2.8.6","aar"),
    ("androidx.lifecycle","lifecycle-viewmodel-savedstate","2.8.6","aar"),
    ("androidx.lifecycle","lifecycle-service","2.8.6","aar"),
    ("androidx.lifecycle","lifecycle-process","2.8.6","aar"),
    ("androidx.core","core","1.13.1","aar"),
    ("androidx.core","core-ktx","1.13.1","aar"),
    ("androidx.appcompat","appcompat","1.7.0","aar"),
    ("androidx.appcompat","appcompat-resources","1.7.0","aar"),
    ("androidx.activity","activity","1.9.2","aar"),
    ("androidx.activity","activity-ktx","1.9.2","aar"),
    ("androidx.fragment","fragment","1.8.3","aar"),
    ("androidx.fragment","fragment-ktx","1.8.3","aar"),
    ("androidx.recyclerview","recyclerview","1.3.2","aar"),
    ("androidx.preference","preference","1.2.1","aar"),
    ("androidx.preference","preference-ktx","1.2.1","aar"),
    ("androidx.work","work-runtime","2.9.1","aar"),
    ("androidx.work","work-runtime-ktx","2.9.1","aar"),
    ("com.google.android.material","material","1.12.0","aar"),
    ("androidx.versionedparcelable","versionedparcelable","1.1.1","aar"),
    ("androidx.drawerlayout","drawerlayout","1.1.1","aar"),
    ("androidx.viewpager","viewpager","1.0.0","aar"),
    ("androidx.viewpager2","viewpager2","1.0.0","aar"),
    ("androidx.customview","customview","1.1.0","aar"),
    ("androidx.coordinatorlayout","coordinatorlayout","1.1.0","aar"),
    ("androidx.transition","transition","1.4.1","aar"),
    ("androidx.vectordrawable","vectordrawable","1.1.0","aar"),
    ("androidx.vectordrawable","vectordrawable-animated","1.1.0","aar"),
    ("androidx.cursoradapter","cursoradapter","1.0.0","aar"),
    ("androidx.loader","loader","1.1.0","aar"),
    ("androidx.savedstate","savedstate","1.2.1","aar"),
    ("androidx.savedstate","savedstate-ktx","1.2.1","aar"),
    ("androidx.tracing","tracing","1.2.0","aar"),
    ("androidx.startup","startup-runtime","1.1.1","aar"),
    ("androidx.sqlite","sqlite","2.4.0","aar"),
    ("androidx.sqlite","sqlite-framework","2.4.0","aar"),
    ("androidx.emoji2","emoji2","1.3.0","aar"),
    ("androidx.emoji2","emoji2-views-helper","1.3.0","aar"),
    ("androidx.resourceinspection","resourceinspection-annotation","1.0.1","jar"),
    ("androidx.interpolator","interpolator","1.0.0","aar"),
    ("androidx.cardview","cardview","1.0.0","aar"),
    ("androidx.dynamicanimation","dynamicanimation","1.0.0","aar"),
    ("androidx.legacy","legacy-support-core-utils","1.0.0","aar"),
    ("androidx.documentfile","documentfile","1.0.0","aar"),
    ("androidx.localbroadcastmanager","localbroadcastmanager","1.1.0","aar"),
    ("androidx.print","print","1.0.0","aar"),
    ("com.google.guava","listenablefuture","1.0","jar"),
]

def get(url, dest):
    try:
        with urllib.request.urlopen(url, timeout=90) as r, open(dest, "wb") as f:
            shutil.copyfileobj(r, f)
        return True
    except Exception:
        return False

def one(a):
    g, art, v, p = a
    dest = os.path.join(OUT, f"{art}-{v}.jar")
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return (art, "cached")
    tmp = dest + ".dl"
    for tmpl in (G, M):
        url = tmpl.format(g=g.replace(".", "/"), a=art, v=v, p=p)
        if not get(url, tmp):
            continue
        if p == "aar":
            try:
                z = zipfile.ZipFile(tmp)
                if "classes.jar" not in z.namelist():
                    continue
                with z.open("classes.jar") as src, open(dest, "wb") as out:
                    shutil.copyfileobj(src, out)
            except Exception:
                continue
        else:
            os.replace(tmp, dest)
        os.path.exists(tmp) and os.remove(tmp)
        return (art, "ok")
    return (art, "FAIL")

with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
    res = list(ex.map(one, ARTS))
ok = [r for r in res if r[1] != "FAIL"]
bad = [r[0] for r in res if r[1] == "FAIL"]
print(f"fetched {len(ok)}/{len(ARTS)}")
if bad:
    print("failed:", ", ".join(bad))
print("LIBS_READY")
