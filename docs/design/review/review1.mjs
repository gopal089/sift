// Review screenshots of every screen and state (checks 1-14). Usage: node review1.mjs OUTDIR
import { launch, sleep } from "./cdp.mjs";
const out = process.argv[2];
const B = "http://localhost:8080/";
const Q = "?q=how+do+plants+make+food";
const p = await launch();
const log = {};

async function states(dark) {
  const d = dark ? "-dark" : "";
  await p.view({ width: 1280, height: 800, dark }); await p.goto(B); await p.shot(`${out}/01-home-1280${d}.png`);
  await p.view({ width: 375, height: 740, dark }); await p.goto(B); await p.shot(`${out}/02-home-375${d}.png`);
  await p.view({ width: 1280, height: 900, dark }); await p.goto(B + Q); await p.shot(`${out}/03-results-1280${d}.png`);
  await p.view({ width: 375, height: 812, dark }); await p.goto(B + Q); await p.shot(`${out}/04-results-375${d}.png`);
  await p.view({ width: 768, height: 900, dark }); await p.goto(B + Q); await p.shot(`${out}/05-results-768${d}.png`);

  // 7. autocomplete open, second suggestion highlighted with the keyboard
  for (const w of [1280, 375]) {
    await p.view({ width: w, height: w > 400 ? 700 : 740, dark }); await p.goto(B);
    await p.click("#q"); await p.type("pho"); await sleep(700); await p.key("ArrowDown", 2); await sleep(200);
    await p.shot(`${out}/07-autocomplete-${w}${d}.png`);
    log[`autocomplete-${w}${d}`] = await p.eval(`[...document.querySelectorAll('[role=option]')].map(o => o.textContent + (o.getAttribute('aria-selected') === 'true' ? ' (selected)' : ''))`);
  }
  // 8. did you mean
  await p.view({ width: 1280, height: 700, dark }); await p.goto(B + "?q=pyhton"); await p.shot(`${out}/08-did-you-mean-1280${d}.png`);
  await p.view({ width: 375, height: 740, dark }); await p.goto(B + "?q=pyhton"); await p.shot(`${out}/08-did-you-mean-375${d}.png`);
  // 9. no results (keyword mode)
  await p.view({ width: 1280, height: 700, dark }); await p.goto(B + "?q=qqxzvw&mode=keyword"); await p.shot(`${out}/09-no-results-1280${d}.png`);
  await p.view({ width: 375, height: 740, dark }); await p.goto(B + "?q=qqxzvw&mode=keyword"); await p.shot(`${out}/09-no-results-375${d}.png`);
  // 11. loading: first search from home (3 s network delay), then Next with old results dimmed
  await p.view({ width: 1280, height: 700, dark }); await p.goto(B);
  await p.latency(3000);
  await p.click("#q"); await p.type("volcano"); await p.key("Enter"); await sleep(900);
  await p.shot(`${out}/11-loading-first-1280${d}.png`);
  log[`loading-first${d}`] = await p.eval(`document.querySelector('main').getAttribute('aria-busy') + ' | ' + document.querySelector('main').innerText.slice(0, 40)`);
  await p.latency(0); await sleep(3500);
  await p.latency(3000);
  await p.click(".pager-next"); await sleep(900);
  await p.shot(`${out}/11-loading-next-1280${d}.png`);
  log[`loading-next${d}`] = await p.eval(`getComputedStyle(document.querySelector('.results')).opacity + ' | busy=' + document.querySelector('main').getAttribute('aria-busy')`);
  await p.latency(0); await sleep(3500);
}

await states(false);
await states(true);

// 12. a 200-character query (the input's limit)
const long = ("photosynthesis in green plants and algae uses sunlight water and carbon dioxide to make sugar ").repeat(3).slice(0, 200);
for (const w of [1280, 375]) {
  await p.view({ width: w, height: 800 }); await p.goto(B + "?q=" + encodeURIComponent(long) + "&mode=keyword");
  await p.shot(`${out}/12-long-query-${w}.png`);
  log[`long-query-${w}`] = await p.eval(`({ len: document.querySelector('#q').value.length, overflowX: document.documentElement.scrollWidth > innerWidth, status: document.querySelector('[role=status]')?.textContent })`);
}
// 13. a very long title
for (const w of [1280, 375, 320]) {
  await p.view({ width: w, height: 700 }); await p.goto(B + "?q=revocations+of+appointments+to+orders+and+awarded+decorations&mode=keyword");
  await p.shot(`${out}/13-long-title-${w}.png`);
  log[`long-title-${w}`] = await p.eval(`({ first: document.querySelector('.result-title').textContent, overflowX: document.documentElement.scrollWidth > innerWidth })`);
}
// 14. page 2 and the last page
await p.view({ width: 1280, height: 900 });
await p.goto(B + "?q=photosynthesis&mode=keyword&page=2"); await p.shot(`${out}/14-page-2.png`, { full: true });
log["page-2"] = await p.eval(`document.querySelector('.pager').innerText`);
await p.goto(B + "?q=photosynthesis&mode=keyword&page=17"); await p.shot(`${out}/14-last-page.png`, { full: true });
log["last-page"] = await p.eval(`document.querySelector('.pager').innerText + ' | results on page: ' + document.querySelectorAll('.result').length`);

console.log(JSON.stringify(log, null, 1));
p.close();
