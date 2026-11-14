// k6 load test for GET /search.
//
//   k6 run -e BASE=http://localhost:8080 -e MODE=keyword -e VUS=10 -e OUT=loadtest/results/keyword-10.json loadtest/search.js
//
// Each virtual user (VU) is a loop: send a search, wait for the answer, send the next (no pause = the most
// load that many users can create). Queries come from loadtest/queries.json and are picked with a Zipf-like
// skew: query number r (1 = most popular) is chosen with probability proportional to 1 / r. Real search
// traffic looks like this (a few queries are very common), so the cache sees a realistic mix of hits and misses.
import http from "k6/http";
import { check } from "k6";
import { Rate } from "k6/metrics";
import { SharedArray } from "k6/data";

const queries = new SharedArray("queries", () => JSON.parse(open("./queries.json")));
const MODE = __ENV.MODE || "keyword";
const BASE = __ENV.BASE || "http://localhost:8080";

// cumulative weights for the 1/r distribution, built once per VU
const cdf = [];
let total = 0;
for (let r = 1; r <= queries.length; r++) {
  total += 1 / r;
  cdf.push(total);
}

function pick() {
  const x = Math.random() * total;
  let lo = 0;
  let hi = cdf.length - 1;
  while (lo < hi) {               // binary search: first index whose cumulative weight >= x
    const mid = (lo + hi) >> 1;
    if (cdf[mid] < x) lo = mid + 1;
    else hi = mid;
  }
  return queries[lo];
}

export const options = {
  vus: Number(__ENV.VUS || 10),
  duration: __ENV.DURATION || "60s",
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
};

const cacheHits = new Rate("cache_hits");

export default function () {
  const q = pick();
  const res = http.get(`${BASE}/search?q=${encodeURIComponent(q)}&mode=${MODE}`, { tags: { name: "search" } });
  const ok = check(res, { "status 200": (r) => r.status === 200 });
  if (ok) {
    cacheHits.add(res.json("stats.cache") === "hit");
  }
}

export function handleSummary(data) {
  const out = {};
  if (__ENV.OUT) out[__ENV.OUT] = JSON.stringify(data, null, 1);
  out.stdout = `\n${MODE} mode, ${options.vus} VUs, ${options.duration}\n`
    + `  requests/s ${data.metrics.http_reqs.values.rate.toFixed(1)}`
    + `  p50 ${data.metrics.http_req_duration.values.med.toFixed(2)} ms`
    + `  p95 ${data.metrics.http_req_duration.values["p(95)"].toFixed(2)} ms`
    + `  p99 ${data.metrics.http_req_duration.values["p(99)"].toFixed(2)} ms`
    + `  errors ${(data.metrics.http_req_failed.values.rate * 100).toFixed(2)} %`
    + `  cache hits ${(data.metrics.cache_hits ? data.metrics.cache_hits.values.rate * 100 : 0).toFixed(1)} %\n`;
  return out;
}
