"""Start the API, send Smart and keyword queries over HTTP, report heap, RSS and query times.

Usage: python3 eval/measure_api.py JAVA_HOME int8|float32 PORT   (after ./gradlew :api:installDist)
Queries: the main 50 + 150 check queries (titles and phrases, no held-out queries), 3 rounds per mode.
"""
import json, os, statistics, subprocess, sys, time, urllib.parse, urllib.request
java_home, kind, port = sys.argv[1], sys.argv[2], sys.argv[3]
env = dict(os.environ, JAVA_HOME=java_home, SIFT_VECTOR_KIND=kind, PORT=port)
t0 = time.time()
p = subprocess.Popen(["api/build/install/sift-api/bin/sift-api"], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
base = f"http://localhost:{port}"
def get(path):
    with urllib.request.urlopen(base + path, timeout=30) as r:
        return json.loads(r.read())
for _ in range(600):
    try:
        get("/health"); break
    except Exception:
        time.sleep(0.1)
def rss_mb(): return int(subprocess.check_output(["ps", "-o", "rss=", "-p", str(p.pid)]).strip()) / 1024
def heap_mb():
    subprocess.run([f"{java_home}/bin/jcmd", str(p.pid), "GC.run"], capture_output=True)
    out = subprocess.check_output([f"{java_home}/bin/jcmd", str(p.pid), "GC.heap_info"], text=True)
    for line in out.splitlines():
        if "used" in line and ("garbage-first heap" in line or "heap" in line.lower()):
            used = line.split("used")[1].split("K")[0].strip()
            return int(used) / 1024
    return None
stats = get("/stats")
start_rss, start_heap = rss_mb(), heap_mb()
queries = [q["query"] for q in json.load(open("eval/queries.json"))]
queries += [json.loads(l)["text"] for l in open("data/vectors/check-queries.jsonl")][50:]
for q in queries[:20]:   # warm-up
    get("/search?" + urllib.parse.urlencode({"q": q, "mode": "smart"}))
took, wall = {"smart": [], "keyword": []}, {"smart": [], "keyword": []}
for rnd in range(3):
    for mode in ("smart", "keyword"):
        for q in queries:
            s = time.perf_counter()
            r = get("/search?" + urllib.parse.urlencode({"q": q, "mode": mode}))
            wall[mode].append((time.perf_counter() - s) * 1000)
            took[mode].append(r["stats"]["tookMs"])
def pct(xs, p): xs = sorted(xs); return xs[min(len(xs) - 1, int(len(xs) * p + 0.999999) - 1)]
res = {"vectorKind": kind, "startupSeconds": stats["startupSeconds"], "vectorBytes": stats["vectorBytes"],
       "smartRanking": stats["smartRanking"], "afterStart": {"rssMb": round(start_rss, 1), "heapUsedAfterGcMb": round(start_heap, 1)},
       "afterQueries": {"rssMb": round(rss_mb(), 1), "heapUsedAfterGcMb": round(heap_mb(), 1)},
       "queries": len(queries), "rounds": 3}
for mode in ("smart", "keyword"):
    res[mode] = {"serverTookMs": {"median": round(statistics.median(took[mode]), 2), "p95": round(pct(took[mode], 0.95), 2), "max": round(max(took[mode]), 2)},
                 "httpWallMs": {"median": round(statistics.median(wall[mode]), 2), "p95": round(pct(wall[mode], 0.95), 2)}}
p.terminate(); p.wait()
print(json.dumps(res, indent=1))
