// Re-runs the design-review checks the definition card can affect (15-18, 20-23, headings, phone position).
// Same helpers and measurements as review2.mjs. Usage: node review-card.mjs OUTDIR > result.json
import { launch, sleep } from "./cdp.mjs";
const out = process.argv[2];
const B = "http://localhost:8080/";
const p = await launch(9336);
const R = {};
const CARD = [["photosynthesis", "?q=photosynthesis"], ["einstein", "?q=einstein"], ["world war 2", "?q=world+war+2"],
  ["martin luther king", "?q=martin+luther+king"], ["keyword tiger", "?q=tiger&mode=keyword"]];

const FOCUS = `(() => {
  const el = document.activeElement;
  const ringOn = el.matches('#q') ? el.closest('.field') : el.matches('input[type=radio]') ? el.closest('label') : el;
  const cs = getComputedStyle(ringOn);
  const name = el.getAttribute('aria-label') || (el.matches('input[type=radio]') ? el.closest('label').textContent : '') || el.textContent.trim().slice(0, 40) || el.id;
  return { tag: el.tagName.toLowerCase(), name, ring: cs.outlineStyle !== 'none' && parseFloat(cs.outlineWidth) >= 2 ? cs.outlineWidth + ' ' + cs.outlineColor : 'NONE' };
})()`;

// screenshots of the card in every width and both themes
for (const dark of [false, true]) {
  const d = dark ? "-dark" : "";
  for (const [w, h] of [[1280, 900], [768, 900], [375, 812], [320, 700]]) {
    await p.view({ width: w, height: h, dark });
    await p.goto(B + "?q=photosynthesis");
    await p.shot(`${out}/card-photosynthesis-${w}${d}.png`);
  }
}
await p.view({ width: 375, height: 812 });
await p.goto(B + "?q=martin+luther+king"); await p.shot(`${out}/card-long-related-375.png`);
await p.view({ width: 1280, height: 900 });
await p.goto(B + "?q=einstein"); await p.shot(`${out}/card-redirect-einstein-1280.png`);

// 15-17. Tab order and focus rings on a page with a card (keyboard only)
await p.view({ width: 1280, height: 900 });
await p.goto(B + "?q=photosynthesis");
const stops = [];
for (let i = 0; i < 12; i++) { await p.key("Tab"); stops.push(await p.eval(FOCUS)); }
R["tab-order card page"] = stops;
await p.goto(B + "?q=photosynthesis");
for (let i = 0; i < 15; i++) { await p.key("Tab"); if ((await p.eval(FOCUS)).name === "Calvin cycle") break; }
R["focus on a related article"] = await p.eval(FOCUS);
await p.shot(`${out}/card-focus-related.png`);
await p.key("Enter"); await sleep(1200);
R["Enter on a related article"] = await p.eval(`location.search + ' | card: ' + (document.querySelector('.definition-title')?.textContent ?? 'none')`);

// 18. Accessibility tree and heading order
await p.goto(B + "?q=photosynthesis");
await p.send("Accessibility.enable");
const { result } = await p.send("Accessibility.getFullAXTree");
const controls = result.nodes.filter((n) => !n.ignored && ["link", "button", "combobox", "radio", "option"].includes(n.role?.value));
R["a11y card page unnamed controls"] = controls.filter((n) => !n.name?.value).length;
R["a11y card region"] = result.nodes.filter((n) => !n.ignored && n.role?.value === "region").map((n) => n.name?.value);
R["headings in order"] = await p.eval(`[...document.querySelectorAll('h1, h2, h3')].slice(0, 6).map(h => h.tagName + ' ' + h.textContent)`);

// 20. Tap targets at phone width on card pages
const TAPS = `(() => {
  const els = [...document.querySelectorAll('a, button, input:not([type=radio]), label.mode')].filter(e => e.offsetParent || e.getClientRects().length);
  return els.map(e => {
    const rects = [...e.getClientRects()];
    const h = Math.min(...rects.map(r => r.height)), w = Math.max(...rects.map(r => r.width));
    const inline = getComputedStyle(e).display === 'inline';
    return { name: (e.getAttribute('aria-label') || e.textContent).trim().slice(0, 30), w: Math.round(w), h: Math.round(h), inline, ok: h >= 44 && (inline || w >= 44) };
  });
})()`;
await p.view({ width: 375, height: 812 });
for (const [label, url] of CARD) {
  await p.goto(B + url);
  const taps = await p.eval(TAPS);
  R[`taps ${label}: failing`] = taps.filter((t) => !t.ok);
  R[`taps ${label}: smallest related`] = (await p.eval(`(() => { const hs = [...document.querySelectorAll('.related a')].map(a => Math.round(a.getBoundingClientRect().height)); return hs.length ? Math.min(...hs) : null; })()`));
}

// Where the first result starts on a phone, with and without a card
for (const [label, url] of [["with card: photosynthesis", "?q=photosynthesis"], ["no card: how do plants make food", "?q=how+do+plants+make+food"]]) {
  await p.goto(B + url);
  R[`375 px: first result top, ${label}`] = await p.eval(`Math.round(document.querySelector('.result-title').getBoundingClientRect().top)`);
}

// 21-22. No horizontal scroll at 320 px and at 200 % zoom
const OVER = `document.documentElement.scrollWidth - innerWidth`;
await p.view({ width: 320, height: 700 });
for (const [label, url] of CARD) { await p.goto(B + url); R[`320 overflow px: ${label}`] = await p.eval(OVER); }
await p.view({ width: 640, height: 450, scale: 2 });
for (const [label, url] of CARD) { await p.goto(B + url); R[`zoom 200% overflow px: ${label}`] = await p.eval(OVER); }
await p.goto(B + "?q=photosynthesis"); await p.shot(`${out}/card-zoom-200.png`);

// 23. Layout shift while the card page loads
const CLS = `new Promise(r => { let s = 0; new PerformanceObserver(l => { for (const e of l.getEntries()) if (!e.hadRecentInput) s += e.value; })
  .observe({ type: 'layout-shift', buffered: true }); setTimeout(() => r(Math.round(s * 10000) / 10000), 500); })`;
for (const lat of [0, 800]) {
  await p.latency(lat);
  for (const [label, w, h] of [["desktop", 1280, 900], ["phone", 375, 812]]) {
    await p.view({ width: w, height: h });
    await p.goto(B + "?q=photosynthesis", 400 + 2 * lat);
    R[`CLS card page ${label}, ${lat} ms network delay`] = await p.eval(CLS);
  }
}
await p.latency(0);

console.log(JSON.stringify(R, null, 1));
p.close();
