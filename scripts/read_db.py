import sqlite3

c = sqlite3.connect(r"d:\anz\project\LifeBoard\scripts\mc.db")
print("== modules ==")
for r in c.execute("select module_pkg_name, apk_path from modules"):
    print(r)
print("== modules_state ==")
for r in c.execute("select module_pkg_name, user_id, enabled, scope_request_blocked from modules_state"):
    print(r)
print("== scope ==")
for r in c.execute("select module_pkg_name, app_pkg_name, user_id from scope order by module_pkg_name"):
    print(r)
