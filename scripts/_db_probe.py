import sqlite3
import datetime

con = sqlite3.connect('db.db')
cur = con.cursor()

rows = list(cur.execute(
    "select time, level, tag, message from app_log order by time asc"
))

def fmt(ms):
    return datetime.datetime.fromtimestamp(ms / 1000).strftime('%m-%d %H:%M:%S')

plugin = [r for r in rows if r[2] == 'AppLogBridge']

starts = [i for i, r in enumerate(plugin) if '登录开始' in (r[3] or '')]
if not starts:
    print('没有登录记录')
    raise SystemExit

last = starts[-1]
print(f'最后一次登录（共 {len(plugin) - last} 条）\n')
for t, lvl, tag, msg in plugin[last:]:
    m = (msg or '').replace('\n', ' | ')
    # context 太长，截断
    if 'context=' in m:
        i = m.index('context=')
        m = m[:i] + 'context=<略>'
    print(f'[{fmt(t)}] {lvl:5} {m[:300]}')
