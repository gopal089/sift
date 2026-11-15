// Measured review checks: keyboard, focus, tab order, accessibility tree, tap targets, 320 px, 200 % zoom,
// layout shift, reduced motion, history, error state. Usage: node review2.mjs OUTDIR > result.json
import { execSync } from "node:child_process";
import { launch, sleep } from "./cdp.mjs";
const out = process.argv[2];
const B = "http://localhost:8080/";
const p = await launch(9335);
const R = {};

/** A short description of the focused element and whether a focus ring is drawn for it. */
const FOCUS = `(() => {
  const el = document.activeElement;
  const ringOn = el.matches('#q') ? el.closest('.field') : el.matches('input[type=radio]') ? el.closest('label') : el;
  const cs = getComputedStyle(ringOn);
  const name = el.getAttribute('aria-label') || (el.matches('input[type=radio]') ? el.closest('label').textContent : '') || el.textContent.trim().slice(0, 40) || el.id;
  return { tag: el.tagName.toLowerCase(), name, ring: cs.outlineStyle !== 'none' && parseFloat(cs.outlineWidth) >= 2 ? cs.outlineWidth + ' ' + cs.outlineColor : 'NONE' };
})()`;

async function tabWalk(label, url, count) {
  await p.goto(url);
  const stops = [];
  for (let i = 0; i < count; i++) { await p.key("Tab"); stops.push(await p.eval(FOCUS)); }
  R[`tab-order ${label}`] = stops;
}

// 15-17. Tab order and focus rings, home and results (keyboard only)
await p.view({ width: 1280, height: 900 });
await tabWalk("home", B, 9);
await tabWalk("results", B + "?q=photosynthesis&mode=keyword", 7);

// 15. Full keyboard flow: type, pick a suggestion, open Details, switch mode, page with Next, Escape
await p.goto(B);
await p.key("Tab", 2); // wordmark, search box
await p.type("pho"); await sleep(700);
R["flow: suggestions"] = await p.eval(`[...document.querySelectorAll('[role=option]')].map(o => o.textContent)`);
await p.key("ArrowDown", 2); await p.key("Enter"); await sleep(1200);
R["flow: after Enter"] = await p.eval(`location.search + ' | first: ' + document.querySelector('.result-title')?.textContent`);
await p.shot(`${out}/15-keyboard-results.png`);
await p.key("Tab", 1); // search button
await p.key("Tab", 1); // mode radio group (Smart)
R["flow: on mode"] = await p.eval(FOCUS);
await p.key("ArrowRight"); await sleep(1200);
R["flow: ArrowRight on mode"] = await p.eval(`location.search + ' | checked: ' + document.querySelector('input[type=radio]:checked').value`);
await p.shot(`${out}/16-focus-mode-tab.png`);
await p.key("Tab"); // Details
R["flow: on Details"] = await p.eval(FOCUS);
await p.key("Enter"); await sleep(300);
R["flow: Details open"] = await p.eval(`document.querySelector('.text-button').getAttribute('aria-expanded') + ' | ' + document.querySelector('.details').innerText.replace(/\\n/g, ' ')`);
await p.shot(`${out}/16-focus-details.png`);
await p.key("Tab");
R["flow: first result"] = await p.eval(FOCUS);
await p.shot(`${out}/16-focus-result.png`);
// jump to Next with Tab
for (let i = 0; i < 15; i++) { const f = await p.eval(FOCUS); if (f.name === "Next") break; await p.key("Tab"); }
R["flow: on Next"] = await p.eval(FOCUS);
await p.shot(`${out}/16-focus-next.png`);
await p.key("Enter"); await sleep(1200);
R["flow: after Next"] = await p.eval(`location.search + ' | focused: ' + document.activeElement.textContent + ' | scrollY ' + scrollY`);
await p.goto(B);
await p.key("Tab", 2); await p.type("pyt"); await sleep(700); await p.key("Escape"); await sleep(200);
R["flow: Escape closes list"] = await p.eval(`document.querySelector('[role=listbox]') === null && document.querySelector('#q').getAttribute('aria-expanded')`);

// 18. Accessibility tree: every control has a role and a name
async function axTree(label, url, setup) {
  await p.goto(url);
  if (setup) await setup();
  await p.send("Accessibility.enable");
  const { result } = await p.send("Accessibility.getFullAXTree");
  const interesting = new Set(["link", "button", "combobox", "radio", "radiogroup", "listbox", "option", "navigation", "main", "banner",
    "contentinfo", "search", "status", "alert", "heading", "list", "group", "LabelText", "DescriptionList", "term", "definition"]);
  R[`a11y ${label}`] = result.nodes.filter((n) => !n.ignored && interesting.has(n.role?.value))
    .map((n) => `${n.role.value}: ${n.name?.value ?? ""}`.trim()).filter((s, i, a) => a.indexOf(s) === i);
  const controls = result.nodes.filter((n) => !n.ignored && ["link", "button", "combobox", "radio", "option"].includes(n.role?.value));
  R[`a11y ${label} unnamed controls`] = controls.filter((n) => !n.name?.value).length;
}
await axTree("home", B);
await axTree("results", B + "?q=volcano&mode=keyword&page=2");
await axTree("autocomplete", B, async () => { await p.click("#q"); await p.type("vol"); await sleep(700); await p.key("ArrowDown"); });

// 20. Tap targets at phone width: every link, button, input and tab, 44 x 44 at least (inline links: height)
const TAPS = `(() => {
  const els = [...document.querySelectorAll('a, button, input:not([type=radio]), label.mode, [role=option]')].filter(e => e.offsetParent || e.getClientRects().length);
  return els.map(e => {
    const rects = [...e.getClientRects()];
    const h = Math.min(...rects.map(r => r.height)), w = Math.max(...rects.map(r => r.width));
    const inline = getComputedStyle(e).display === 'inline';
    return { name: (e.getAttribute('aria-label') || e.textContent).trim().slice(0, 30), w: Math.round(w), h: Math.round(h), inline, ok: h >= 44 && (inline || w >= 44) };
  });
})()`;
await p.view({ width: 375, height: 812 });
for (const [label, url] of [["home", B], ["results", B + "?q=photosynthesis&mode=keyword&page=2"], ["did-you-mean", B + "?q=pyhton"],
  ["no-results", B + "?q=qqxzvw&mode=keyword"]]) {
  await p.goto(url);
  if (label === "results") { await p.click(".text-button"); }
  const taps = await p.eval(TAPS);
  R[`taps ${label}: smallest`] = taps.reduce((m, t) => (t.h < m.h ? t : m));
  R[`taps ${label}: failing`] = taps.filter((t) => !t.ok);
}
await p.goto(B); await p.click("#q"); await p.type("pho"); await sleep(700);
R["taps autocomplete: failing"] = (await p.eval(TAPS)).filter((t) => !t.ok);

// 21. No horizontal scroll at 320 px, in every state
await p.view({ width: 320, height: 700 });
const OVER = `document.documentElement.scrollWidth - innerWidth`;
const long = encodeURIComponent("photosynthesis in green plants and algae uses sunlight water and carbon dioxide to make sugar ".repeat(3).slice(0, 200));
for (const [label, url] of [["home", B], ["results", B + "?q=how+do+plants+make+food"], ["did-you-mean", B + "?q=pyhton"],
  ["no-results", B + "?q=qqxzvw&mode=keyword"], ["long-query", B + "?q=" + long + "&mode=keyword"],
  ["long-title", B + "?q=revocations+of+appointments+to+orders+and+awarded+decorations&mode=keyword"],
  ["page-2", B + "?q=photosynthesis&mode=keyword&page=2"]]) {
  await p.goto(url);
  if (label === "results") await p.click(".text-button");
  R[`320 overflow px: ${label}`] = await p.eval(OVER);
}
await p.goto(B); await p.click("#q"); await p.type("pho"); await sleep(700);
R["320 overflow px: autocomplete"] = await p.eval(OVER);
await p.shot(`${out}/21-320-autocomplete.png`);
await p.goto(B + "?q=how+do+plants+make+food"); await p.click(".text-button"); await sleep(200);
await p.shot(`${out}/21-320-results-details.png`, { full: false });

// 22. Zoom 200 %: a 1280 px window at 200 % is a 640 px CSS viewport at 2x
await p.view({ width: 640, height: 450, scale: 2 });
for (const [label, url] of [["home", B], ["results", B + "?q=how+do+plants+make+food"]]) {
  await p.goto(url);
  if (label === "results") await p.click(".text-button");
  R[`zoom 200%: ${label} overflow px`] = await p.eval(OVER);
  await p.shot(`${out}/22-zoom-200-${label}.png`);
}

// 23. Layout shift while loading (the article count arrives late, search results arrive late)
await p.view({ width: 1280, height: 900 });
const CLS = `new Promise(r => { let s = 0; new PerformanceObserver(l => { for (const e of l.getEntries()) if (!e.hadRecentInput) s += e.value; })
  .observe({ type: 'layout-shift', buffered: true }); setTimeout(() => r(Math.round(s * 10000) / 10000), 500); })`;
for (const lat of [0, 800]) {
  await p.latency(lat);
  for (const [label, url] of [["home", B], ["results", B + "?q=volcano"], ["phone results", B + "?q=volcano"]]) {
    if (label.startsWith("phone")) await p.view({ width: 375, height: 812 }); else await p.view({ width: 1280, height: 900 });
    await p.goto(url, 400 + 2 * lat);
    R[`CLS ${label}, ${lat} ms network delay`] = await p.eval(CLS);
  }
}
await p.latency(0);

// 24. Reduced motion
for (const reduced of [false, true]) {
  await p.view({ width: 1280, height: 700, reduced });
  await p.goto(B); await p.click("#q"); await p.type("pho"); await sleep(700);
  const anim = await p.eval(`(() => { const s = getComputedStyle(document.querySelector('.suggestions')); return s.animationName + ' ' + s.animationDuration; })()`);
  await p.goto(B + "?q=volcano");
  const trans = await p.eval(`getComputedStyle(document.querySelector('.disclosure-icon')).transitionDuration`);
  R[`motion ${reduced ? "reduced" : "normal"}`] = { dropdown: anim, detailsArrow: trans };
}

// 28. Shareable URLs and back / forward
await p.view({ width: 1280, height: 900 });
await p.goto(B + "?q=volcano&mode=keyword&page=2");
R["url: opened directly"] = await p.eval(`({ box: document.querySelector('#q').value, mode: document.querySelector('input[type=radio]:checked').value,
  pager: document.querySelector('[aria-current=page]').textContent, title: document.title })`);
await p.click(".pager-next"); await sleep(1000);
R["url: after Next"] = await p.eval(`location.search + ' | ' + document.querySelector('[aria-current=page]').textContent`);
await p.eval(`history.back()`); await sleep(1000);
R["url: after Back"] = await p.eval(`location.search + ' | ' + document.querySelector('[aria-current=page]').textContent`);
await p.eval(`history.forward()`); await sleep(1000);
R["url: after Forward"] = await p.eval(`location.search + ' | ' + document.querySelector('[aria-current=page]').textContent`);
await p.click(".wordmark"); await sleep(600);
R["url: wordmark goes home"] = await p.eval(`location.search + ' | view: ' + document.querySelector('.page').dataset.view + ' | mode kept: ' + document.querySelector('input[type=radio]:checked').value`);
await p.eval(`history.back()`); await sleep(1000);
R["url: Back from home"] = await p.eval(`location.search + ' | box: ' + document.querySelector('#q').value`);

// 10. Error state: the page is open, then the API stops
for (const [w, dark] of [[1280, false], [375, false], [1280, true]]) {
  await p.view({ width: w, height: w > 400 ? 700 : 740, dark });
  await p.goto(B + "?q=volcano");
  execSync(`kill $(cat /tmp/sift.pid)`); await sleep(800);
  await p.click("#q"); await p.eval(`document.querySelector('#q').select()`); await p.type("glacier"); await p.key("Enter"); await sleep(1200);
  R[`error ${w}${dark ? " dark" : ""}`] = await p.eval(`document.querySelector('[role=alert]')?.innerText`);
  await p.shot(`${out}/10-error-${w}${dark ? "-dark" : ""}.png`);
  execSync(`nohup "$(cat /tmp/sift.javahome)/bin/java" -Xmx512m -jar api/build/libs/sift-api.jar > /tmp/sift-server.log 2>&1 & echo $! > /tmp/sift.pid`, { cwd: "/Users/gopalsarma/sift" });
  for (let i = 0; i < 40; i++) { try { if ((await fetch(B + "health")).ok) break; } catch { /* starting */ } await sleep(500); }
  if (w === 1280 && !dark) {
    await p.click(".error .button"); await sleep(1500);
    R["error: Try again after restart"] = await p.eval(`(document.querySelector('[role=alert]') ? 'still error' : 'recovered') + ' | ' + document.querySelector('.result-title')?.textContent`);
  }
}

console.log(JSON.stringify(R, null, 1));
p.close();
