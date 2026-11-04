import sqlite3

db = r"d:\anz\project\LifeBoard\scripts\c.db"
c = sqlite3.connect(db)
c.execute("pragma wal_checkpoint(TRUNCATE)")
c.commit()

print("raw_notification total:", c.execute("select count(*) from raw_notification").fetchone()[0])
print()
print("--- 最近 10 条 ---")
for r in c.execute(
    "select packageName, title, text, matchedSources, postedAt "
    "from raw_notification order by id desc limit 10"
):
    print(r)
print()
print("--- metric ---")
for r in c.execute("select * from metric"):
    print(r)
c.close()
