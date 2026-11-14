"""Cache hit vs miss latency: start the API with the cache on, send each query twice per mode, compare.

Usage: python3 eval/measure_cache.py JAVA_HOME PORT   (after ./gradlew :api:installDist)
Queries: the main 50 + 150 check queries (titles and phrases, no held-out queries). The first request for a
(query, mode) is a miss (computed), the second an identical request, which should be a hit (served from the cache).
tookMs = time measured inside the server; wall = whole HTTP round trip seen by this script.
"""
import json, os, statistics, subprocess, sys, time, urllib.parse, urllib.request

java_home, port = sys.argv[1], sys.argv[2]
env = dict(os.environ, JAVA_HOME=java_home, PORT=port)
p = subprocess.Popen(["api/build/install/sift-api/bin/sift-api"], env=env,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
base = f"http://localhost:{port}"


def get(path):
    with urllib.request.urlopen(base + path, timeout=30) as r:
        return json.loads(r.read())


for _ in range(600):
    try:
        get("/health")
        break
    except Exception:
        time.sleep(0.1)

queries = [q["query"] for q in json.load(open("eval/queries.json"))]
queries += [json.loads(l)["text"] for l in open("data/model-bge/check-queries.jsonl")][50:]
# warm-up on different text, so the JIT is warm but none of the measured queries is cached yet
for w in ["warm up one", "warm up two", "warm up three"] * 7:
    for mode in ("keyword", "smart"):
        get("/search?" + urllib.parse.urlencode({"q": w + " " + mode, "mode": mode}))


def pct(xs, q):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(len(xs) * q + 0.999999) - 1)]


res = {"queries": len(queries)}
for mode in ("keyword", "smart"):
    took = {"miss": [], "hit": []}
    wall = {"miss": [], "hit": []}
    wrong = 0
    for q in queries:
        for expect in ("miss", "hit"):
            s = time.perf_counter()
            r = get("/search?" + urllib.parse.urlencode({"q": q, "mode": mode}))
            wall[expect].append((time.perf_counter() - s) * 1000)
            took[expect].append(r["stats"]["tookMs"])
            wrong += r["stats"]["cache"] != expect
    res[mode] = {k: {"serverTookMs": {"median": round(statistics.median(took[k]), 3), "p95": round(pct(took[k], 0.95), 3)},
                     "httpWallMs": {"median": round(statistics.median(wall[k]), 3), "p95": round(pct(wall[k], 0.95), 3)}}
                 for k in ("miss", "hit")}
    res[mode]["unexpectedCacheState"] = wrong
res["cacheStats"] = get("/stats")["cache"]
p.terminate()
p.wait()
print(json.dumps(res, indent=1))
