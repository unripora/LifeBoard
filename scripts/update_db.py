"""把 LSPosed 数据库里 com.lifeboard.app 的 apk_path 更新为当前真实路径。"""
import sqlite3
import sys

db = r"d:\anz\project\LifeBoard\scripts\mc.db"
new_path = open(r"d:\anz\project\LifeBoard\scripts\newpath.txt").read().strip()

conn = sqlite3.connect(db)
cur = conn.execute(
    "update modules set apk_path = ? where module_pkg_name = ?",
    (new_path, "com.lifeboard.app"),
)
print("rows updated:", cur.rowcount)
conn.commit()
for r in conn.execute(
    "select module_pkg_name, apk_path from modules where module_pkg_name = ?",
    ("com.lifeboard.app",),
):
    print(r)
conn.close()
