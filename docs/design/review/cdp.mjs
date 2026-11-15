// A small Chrome DevTools Protocol driver (no npm packages): viewport, colour scheme, keys, typing, clicks,
// slow network, screenshots, and running JS in the page.
import { spawn } from "node:child_process";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const CH = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const KEYS = {
  Tab: { code: "Tab", vk: 9 }, Enter: { code: "Enter", vk: 13, text: "\r" }, Escape: { code: "Escape", vk: 27 },
  ArrowDown: { code: "ArrowDown", vk: 40 }, ArrowUp: { code: "ArrowUp", vk: 38 }, ArrowRight: { code: "ArrowRight", vk: 39 },
  ArrowLeft: { code: "ArrowLeft", vk: 37 }, " ": { code: "Space", vk: 32, text: " " },
};

export async function launch(port = 9334) {
  const chrome = spawn(CH, ["--headless=new", `--remote-debugging-port=${port}`, "--hide-scrollbars",
    `--user-data-dir=${mkdtempSync(join(tmpdir(), "cdp-"))}`, "about:blank"], { stdio: "ignore" });
  let target;
  for (let i = 0; i < 50 && !target; i++) {
    try { target = (await (await fetch(`http://127.0.0.1:${port}/json`)).json()).find((t) => t.type === "page"); } catch { await sleep(200); }
  }
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((r) => ws.addEventListener("open", r));
  let id = 0; const pending = new Map(); const listeners = [];
  ws.addEventListener("message", (e) => { const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); } else listeners.forEach((l) => l(m)); });
  const send = (method, params = {}) => new Promise((r) => { const i = ++id; pending.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
  await send("Page.enable"); await send("Runtime.enable"); await send("Network.enable");
  const page = {
    send,
    async view({ width, height = 900, scale = 1, dark = false, reduced = false }) {
      this.width = width;
      await send("Emulation.setDeviceMetricsOverride", { width, height, deviceScaleFactor: scale, mobile: width < 768 });
      await send("Emulation.setEmulatedMedia", { features: [{ name: "prefers-color-scheme", value: dark ? "dark" : "light" },
        { name: "prefers-reduced-motion", value: reduced ? "reduce" : "no-preference" }] });
    },
    async latency(ms) {
      await send("Network.emulateNetworkConditions", { offline: false, latency: ms, downloadThroughput: -1, uploadThroughput: -1 });
    },
    async goto(url, wait = 900) {
      const loaded = new Promise((r) => { const l = (m) => { if (m.method === "Page.loadEventFired") { listeners.splice(listeners.indexOf(l), 1); r(); } }; listeners.push(l); });
      await send("Page.navigate", { url }); await loaded; await sleep(wait);
    },
    async eval(expr) {
      const r = await send("Runtime.evaluate", { expression: expr, awaitPromise: true, returnByValue: true, userGesture: true });
      if (r.result?.exceptionDetails) throw new Error(JSON.stringify(r.result.exceptionDetails));
      return r.result?.result?.value;
    },
    async key(name, times = 1) {
      const k = KEYS[name];
      for (let i = 0; i < times; i++) {
        await send("Input.dispatchKeyEvent", { type: "keyDown", key: name, code: k.code, windowsVirtualKeyCode: k.vk, text: k.text });
        await send("Input.dispatchKeyEvent", { type: "keyUp", key: name, code: k.code, windowsVirtualKeyCode: k.vk });
        await sleep(60);
      }
    },
    async type(text) { await send("Input.insertText", { text }); },
    async click(selector) {
      const box = await this.eval(`(() => { const el = document.querySelector(${JSON.stringify(selector)}); el.scrollIntoView({block: "center"});
        const r = el.getBoundingClientRect(); return { x: r.x + r.width / 2, y: r.y + r.height / 2 }; })()`);
      for (const type of ["mouseMoved", "mousePressed", "mouseReleased"]) {
        await send("Input.dispatchMouseEvent", { type, x: box.x, y: box.y, button: "left", clickCount: 1 });
      }
    },
    async shot(path, { full = false } = {}) {
      let clip;
      if (full) { const height = await this.eval("document.documentElement.scrollHeight"); clip = { x: 0, y: 0, width: this.width, height, scale: 1 }; }
      const r = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: full, ...(clip ? { clip } : {}) });
      writeFileSync(path, Buffer.from(r.result.data, "base64"));
    },
    close() { ws.close(); chrome.kill(); },
  };
  return page;
}
