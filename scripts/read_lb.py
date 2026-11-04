import sqlite3

db = r"d:\anz\project\LifeBoard\scripts\lb.db"
conn = sqlite3.connect(db)

tables = [r[0] for r in conn.execute(
    "select name from sqlite_master where type='table' order by name")]
print("tables:", tables)

for t in ("raw_notification", "metric"):
    if t not in tables:
        continue
    print(f"\n===== {t} =====")
    cur = conn.execute(f"select * from {t} order by rowid desc limit 10")
    cols = [d[0] for d in cur.description]
    print("cols:", cols)
    for row in cur:
        print(row)

conn.close()
