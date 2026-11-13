// The DevTools driver of the design smoke runs: a headless Chrome, a DevTools connection and a tab
// that clicks, types, holds and takes screenshots like a person would, and collects every script
// error, console error and failed resource on the way. No packages: Node's own WebSocket.
//
// It came from the directions page's smoke run, which lives in the local working record
// (design/redesign/, kept out of git); design/system/smoke.mjs needs it in every checkout.

import { spawn } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

const VIEWS = {
  wide: { width: 1720, height: 1300, deviceScaleFactor: 1, mobile: false },
  laptop: { width: 1440, height: 900, deviceScaleFactor: 2, mobile: false },
  tablet: { width: 1100, height: 1400, deviceScaleFactor: 1, mobile: false },
  phone: { width: 390, height: 844, deviceScaleFactor: 2, mobile: true },
};

const DESK_FRAME = ".pg-desk .pg-viewport";
const PHONE_FRAME = ".pg-phone .pg-viewport";

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function launchChrome(binary) {
  const profile = mkdtempSync(path.join(tmpdir(), "khandaq-smoke-profile-"));
  const proc = spawn(binary, [
    "--headless=new", "--disable-gpu", "--hide-scrollbars", "--no-first-run", "--no-default-browser-check",
    "--disable-extensions", "--mute-audio", `--user-data-dir=${profile}`, "--remote-debugging-port=0",
    // A tab the driver opens is a background tab; without these its timers and frames are throttled
    // and animations stand still between screenshots.
    "--disable-background-timer-throttling", "--disable-backgrounding-occluded-windows", "--disable-renderer-backgrounding",
    "about:blank",
  ], { stdio: ["ignore", "ignore", "pipe"] });
  proc.once("exit", () => {
    try {
      // Chrome can still be flushing its profile while it exits.
      rmSync(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
    }
    catch (err) {
      console.error(`could not remove the temporary Chrome profile ${profile}: ${err.message}`);
    }
  });

  const endpoint = new Promise((resolve, reject) => {
    let buffer = "";
    const timer = setTimeout(() => reject(new Error("Chrome did not report a DevTools endpoint within 20 s")), 20000);
    proc.stderr.on("data", (chunk) => {
      buffer += chunk.toString();
      const match = /DevTools listening on (ws:\/\/\S+)/.exec(buffer);
      if (match) {
        clearTimeout(timer);
        resolve(match[1]);
      }
    });
    proc.once("error", (err) => {
      clearTimeout(timer);
      reject(err);
    });
    proc.once("exit", (code) => {
      clearTimeout(timer);
      reject(new Error(`Chrome exited before the DevTools endpoint was ready (code ${code})`));
    });
  });
  return { endpoint, stop: () => proc.kill("SIGTERM") };
}

class Cdp {
  static connect(url) {
    return new Promise((resolve, reject) => {
      const socket = new WebSocket(url);
      socket.addEventListener("open", () => resolve(new Cdp(socket)), { once: true });
      socket.addEventListener("error", () => reject(new Error(`cannot connect to ${url}`)), { once: true });
    });
  }

  constructor(socket) {
    this.socket = socket;
    this.nextId = 1;
    this.pending = new Map();
    this.listeners = new Set();
    socket.addEventListener("message", (event) => this.receive(JSON.parse(event.data)));
    socket.addEventListener("close", () => {
      for (const entry of this.pending.values()) {
        entry.reject(new Error(`DevTools connection closed during ${entry.method}`));
      }
      this.pending.clear();
    });
  }

  receive(message) {
    if (message.id === undefined) {
      this.listeners.forEach((listener) => listener(message));
      return;
    }
    const entry = this.pending.get(message.id);
    if (!entry) {
      return;
    }
    this.pending.delete(message.id);
    if (message.error) {
      entry.reject(new Error(`${entry.method}: ${message.error.message}`));
    }
    else {
      entry.resolve(message.result);
    }
  }

  send(method, params = {}, sessionId = undefined) {
    const id = this.nextId;
    this.nextId += 1;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject, method });
      this.socket.send(JSON.stringify(sessionId ? { id, method, params, sessionId } : { id, method, params }));
    });
  }

  listen(listener) {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  close() {
    this.socket.close();
  }
}

// ---- one tab ---------------------------------------------------------------------------------------

// Runs in the page: the centre of the first element matching the selector inside a screen that a
// pointer would actually hit there, or why none could be hit.
function findTarget(host, selector) {
  const scope = host ? document.querySelector(`[data-host="${host}"]`) : document;
  if (!scope) {
    return { error: `no screen "${host}"` };
  }
  const all = Array.from(scope.querySelectorAll(selector));
  const reasons = [];
  for (const el of all) {
    const r = el.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) {
      continue;
    }
    const x = r.left + r.width / 2;
    const y = r.top + r.height / 2;
    if (x < 0 || y < 0 || x > innerWidth || y > innerHeight) {
      reasons.push(`off screen (centre ${Math.round(x)},${Math.round(y)} in ${innerWidth} by ${innerHeight}, page scrolled ${Math.round(scrollY)})`);
      continue;
    }
    const hit = document.elementFromPoint(x, y);
    if (hit && (hit === el || el.contains(hit))) {
      return { x, y };
    }
    const name = hit ? `${hit.tagName.toLowerCase()}${typeof hit.className === "string" && hit.className.trim() ? `.${hit.className.trim().split(/\s+/).join(".")}` : ""}` : "nothing";
    reasons.push(`covered by ${name}`);
  }
  return { error: all.length ? reasons.join("; ") || "not visible" : "no match" };
}

class Page {
  static async open(cdp, args) {
    const { targetId } = await cdp.send("Target.createTarget", { url: "about:blank" });
    const { sessionId } = await cdp.send("Target.attachToTarget", { targetId, flatten: true });
    const page = new Page(cdp, sessionId, args);
    await page.send("Page.bringToFront");
    await page.send("Emulation.setFocusEmulationEnabled", { enabled: true });
    await page.send("Page.enable");
    await page.send("Runtime.enable");
    await page.send("Log.enable");
    return page;
  }

  constructor(cdp, sessionId, args) {
    this.cdp = cdp;
    this.sessionId = sessionId;
    this.args = args;
    this.loads = 0;
    this.step = "start";
    this.problems = [];
    this.shots = [];
    cdp.listen((message) => {
      if (message.sessionId === sessionId) {
        this.observe(message);
      }
    });
  }

  send(method, params = {}) {
    return this.cdp.send(method, params, this.sessionId);
  }

  observe({ method, params }) {
    if (method === "Runtime.exceptionThrown") {
      const details = params.exceptionDetails;
      const text = details.exception && details.exception.description ? details.exception.description.split("\n")[0] : details.text;
      this.problem(`script error: ${text}`);
    }
    else if (method === "Runtime.consoleAPICalled" && (params.type === "error" || params.type === "assert")) {
      this.problem(`console.${params.type}: ${params.args.map((a) => a.value ?? a.description ?? "").join(" ")}`);
    }
    else if (method === "Log.entryAdded" && params.entry.level === "error" && !/favicon\.ico/.test(params.entry.url || "")) {
      this.problem(`browser: ${params.entry.text}${params.entry.url ? ` (${params.entry.url})` : ""}`);
    }
  }

  problem(text) {
    this.problems.push(`${this.step}: ${text}`);
  }

  waitFor(method, timeout = 20000) {
    let stop = () => {};
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        stop();
        reject(new Error(`timed out waiting for ${method}`));
      }, timeout);
      stop = this.cdp.listen((message) => {
        if (message.sessionId === this.sessionId && message.method === method) {
          clearTimeout(timer);
          stop();
          resolve(message.params);
        }
      });
    });
  }

  async evaluate(expression) {
    const { result, exceptionDetails } = await this.send("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true });
    if (exceptionDetails) {
      throw new Error(`evaluate failed in ${this.step}: ${exceptionDetails.exception ? exceptionDetails.exception.description : exceptionDetails.text}`);
    }
    return result.value;
  }

  async view(name, theme, reducedMotion = false) {
    await this.send("Emulation.setDeviceMetricsOverride", VIEWS[name]);
    await this.send("Emulation.setEmulatedMedia", {
      features: [
        { name: "prefers-color-scheme", value: theme },
        { name: "prefers-reduced-motion", value: reducedMotion ? "reduce" : "no-preference" },
      ],
    });
  }

  // A fresh document every time: the query makes each load a real navigation, the hash picks the direction.
  async load(dir) {
    const url = new URL(this.args.url);
    this.loads += 1;
    url.searchParams.set("run", String(this.loads));
    url.hash = dir;
    const loaded = this.waitFor("Page.loadEventFired");
    await this.send("Page.navigate", { url: url.href });
    await loaded;
    await this.evaluate("document.fonts ? document.fonts.ready.then(() => true) : true");
    await sleep(700);
  }

  async target(host, selector) {
    const spot = await this.evaluate(`(${findTarget})(${JSON.stringify(host)}, ${JSON.stringify(selector)})`);
    if (spot.error) {
      this.problem(`cannot reach ${selector} on ${host || "the page"}: ${spot.error}`);
      return null;
    }
    return spot;
  }

  mouse(type, x, y, pressed) {
    return this.send("Input.dispatchMouseEvent", {
      type, x, y, button: type === "mouseMoved" && !pressed ? "none" : "left", buttons: pressed ? 1 : 0, clickCount: type === "mouseMoved" ? 0 : 1,
    });
  }

  async click(host, selector) {
    const spot = await this.target(host, selector);
    if (!spot) {
      return false;
    }
    await this.mouse("mouseMoved", spot.x, spot.y, false);
    await this.mouse("mousePressed", spot.x, spot.y, true);
    await this.mouse("mouseReleased", spot.x, spot.y, false);
    return true;
  }

  // Presses and holds; `slide` drags left by that many screen pixels while holding.
  async hold(host, selector, ms, { slide = 0, during = null } = {}) {
    const spot = await this.target(host, selector);
    if (!spot) {
      return false;
    }
    await this.mouse("mouseMoved", spot.x, spot.y, false);
    await this.mouse("mousePressed", spot.x, spot.y, true);
    const steps = slide ? 10 : 0;
    for (let i = 1; i <= steps; i += 1) {
      await sleep(ms / (steps * 2));
      await this.mouse("mouseMoved", spot.x - (slide * i) / steps, spot.y, true);
    }
    await sleep(steps ? ms / 2 : ms);
    if (during) {
      await during();
    }
    await this.mouse("mouseReleased", spot.x - slide, spot.y, false);
    return true;
  }

  async type(text) {
    await this.send("Input.insertText", { text });
  }

  async key(key) {
    const code = { Escape: 27, Enter: 13 }[key];
    await this.send("Input.dispatchKeyEvent", { type: "rawKeyDown", key, code: key, windowsVirtualKeyCode: code, nativeVirtualKeyCode: code });
    await this.send("Input.dispatchKeyEvent", { type: "keyUp", key, code: key, windowsVirtualKeyCode: code, nativeVirtualKeyCode: code });
  }

  // Scenario buttons are page chrome, not the interface under test, so a plain click is enough.
  async scenario(name) {
    const ok = await this.evaluate(`(() => { const b = document.querySelector('[data-scenario="${name}"]'); if (b) b.click(); return !!b; })()`);
    if (!ok) {
      this.problem(`no scenario button "${name}"`);
    }
  }

  // Scrolls so a screen sits right under the sticky page header.
  async focus(frameSelector, gap) {
    await this.evaluate(`(() => {
      const frame = document.querySelector(${JSON.stringify(frameSelector)});
      const head = document.querySelector(".pg-head");
      const top = frame.getBoundingClientRect().top + scrollY - (head ? head.getBoundingClientRect().height : 0) - ${gap};
      scrollTo(0, Math.max(0, top));
      return true;
    })()`);
    await sleep(150);
  }

  focusDesk() {
    return this.focus(DESK_FRAME, 12);
  }

  // On a phone the app fills the screen under the header, so it is scrolled flush to it.
  focusPhone() {
    return this.focus(PHONE_FRAME, 0);
  }

  async shot(name, clipSelector = null) {
    const params = { format: "png" };
    if (clipSelector) {
      // Screenshot clips are in document coordinates.
      params.clip = await this.evaluate(`(() => {
        const r = document.querySelector(${JSON.stringify(clipSelector)}).getBoundingClientRect();
        return { x: r.left + scrollX, y: r.top + scrollY, width: r.width, height: r.height, scale: 1 };
      })()`);
    }
    const { data } = await this.send("Page.captureScreenshot", params);
    const file = path.join(this.args.out, `${name}.png`);
    writeFileSync(file, Buffer.from(data, "base64"));
    this.shots.push(file);
  }
}

export { Cdp, Page, VIEWS, launchChrome, sleep };
