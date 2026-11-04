import sys
import zipfile

apk = r"d:\anz\project\LifeBoard\app\build\outputs\apk\debug\app-debug.apk"
target = b"Lcom/lifeboard/app/xposed/LifeBoardEntry;"

with zipfile.ZipFile(apk) as z:
    names = [n for n in z.namelist() if n.startswith("classes") and n.endswith(".dex")]
    names.sort(key=lambda n: (len(n), n))
    for n in names:
        data = z.read(n)
        if target in data:
            print(f"FOUND in {n} (size={len(data)})")
    print("--- xposed_init ---")
    try:
        print(z.read("assets/xposed_init").decode())
    except KeyError:
        print("assets/xposed_init MISSING")
    print("--- META-INF/xposed entries ---")
    meta = [n for n in z.namelist() if n.startswith("META-INF/xposed")]
    print(meta if meta else "none")
