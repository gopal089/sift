"""Memory and speed of a RUNNING server (e.g. the Docker container), from outside.

Usage: python3 eval/measure_container.py BASE_URL CONTAINER_NAME
Sends 600 queries from loadtest/queries.json in each mode (1,200 different requests, so the cache does not
hide the work), then reports server-side tookMs and the container's memory from `docker stats`.
"""
import json, statistics, subprocess, sys, time, urllib.parse, urllib.request

base, name = sys.argv[1], sys.argv[2]


def get(path):
    with urllib.request.urlopen(base + path, timeout=30) as r:
        return json.loads(r.read())


def mem():
    return subprocess.check_output(["docker", "stats", "--no-stream", "--format", "{{.MemUsage}}", name],
                                   text=True).strip()


def pct(xs, q):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(len(xs) * q + 0.999999) - 1)]


queries = json.load(open("loadtest/queries.json", encoding="utf-8"))[:600]
res = {"memAtStart": mem(), "queriesPerMode": len(queries)}
for mode in ("keyword", "smart"):
    took, wall = [], []
    for q in queries:
        s = time.perf_counter()
        r = get("/api/search?" + urllib.parse.urlencode({"q": q, "mode": mode}))
        wall.append((time.perf_counter() - s) * 1000)
        took.append(r["stats"]["tookMs"])
    res[mode] = {"serverTookMs": {"median": round(statistics.median(took), 2), "p95": round(pct(took, 0.95), 2)},
                 "httpWallMs": {"median": round(statistics.median(wall), 2), "p95": round(pct(wall, 0.95), 2)}}
res["memAfterQueries"] = mem()
res["stats"] = {k: v for k, v in get("/stats").items() if k in ("startupSeconds", "heapUsedMb", "cache", "queriesServed")}
print(json.dumps(res, indent=1))
