"""确认 fitness 响应的真实外层结构"""
import json
import sys

sys.path.insert(0, r"d:\anz\project\LifeBoard\external\mihealth-api")
from mihealth_client import MiHealthClient  # noqa: E402

with open(r"d:\anz\project\LifeBoard\mihealth_credential.json", encoding="utf-8") as f:
    c = json.load(f)

C = MiHealthClient(c["ssecurity"], c["service_token"], c["cuser_id"])

r = C.get_fitness_data_by_time("steps", 1793800000000, 1793860000000)
print("type:", type(r).__name__)
print("top-level keys:", list(r.keys()))
print(json.dumps(r, ensure_ascii=False)[:1200])
