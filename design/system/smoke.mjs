#!/usr/bin/env node
// Smoke run of the design system page in headless Chrome, with the driver of the directions smoke.
//
// The page is opened at wide, laptop, tablet and phone sizes in both themes and once more with reduced
// motion. The run fails on any script error, console error, failed resource or sideways page scroll,
// and on any live component that does not reach the state it promises: the four key moments, the ID
// ruler with a real checksum, the command line, the composer, the sheet, the dialog and the toast.
// Full-page screenshots go to the output directory for a visual pass.
//
// Usage: node smoke.mjs [--url URL] [--out DIR] [--chrome PATH]

import { mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { Cdp, Page, VIEWS, launchChrome, sleep } from "../redesign/directions/smoke.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const DEFAULT_CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";

const OVERVIEW = [
  ["wide", "light"], ["laptop", "light"], ["tablet", "light"], ["phone", "light"],
  ["wide", "dark"], ["phone", "dark"],
];

function parseArgs(argv) {
  const args = {
    url: pathToFileURL(path.join(HERE, "index.html")).href,
    out: path.join(tmpdir(), "khandaq-system-smoke"),
    chrome: process.env.CHROME || DEFAULT_CHROME,
  };
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i];
    const value = argv[i + 1];
    if (!["--url", "--out", "--chrome"].includes(key)) {
      throw new Error(`unknown argument: ${key}`);
    }
    if (value === undefined) {
      throw new Error(`${key} needs a value`);
    }
    args[key.slice(2)] = value;
  }
  return args;
}

// ---- helpers on top of the shared driver -----------------------------------------------------------------

async function fresh(page, step) {
  page.step = step;
  await page.load("");
  await page.evaluate("(() => { try { localStorage.clear(); } catch (e) {} return true; })()");
}

async function reveal(page, selector) {
  const found = await page.evaluate(`(() => {
    const el = document.querySelector(${JSON.stringify(selector)});
    if (!el) return false;
    el.scrollIntoView({ block: "center" });
    return true;
  })()`);
  if (!found) {
    page.problem(`no element ${selector}`);
  }
  await sleep(150);
  return found;
}

async function press(page, selector) {
  if (await reveal(page, selector)) {
    await page.click(null, selector);
  }
}

async function expect(page, what, expression) {
  const ok = await page.evaluate(`(() => { try { return Boolean(${expression}); } catch (e) { return false; } })()`);
  if (!ok) {
    page.problem(`expected ${what}`);
  }
}

// The whole page in one image: the viewport is stretched to the document for the capture.
async function fullShot(page, name, view) {
  const height = await page.evaluate("document.documentElement.scrollHeight");
  await page.send("Emulation.setDeviceMetricsOverride", { ...VIEWS[view], deviceScaleFactor: 1, height: Math.min(height, 16000) });
  await sleep(250);
  await page.shot(name);
  await page.send("Emulation.setDeviceMetricsOverride", VIEWS[view]);
  await sleep(150);
}

// ---- what is played ----------------------------------------------------------------------------------------

async function overview(page, view, theme) {
  await page.view(view, theme);
  await fresh(page, `${view} ${theme}`);
  const sideways = await page.evaluate("document.documentElement.scrollWidth - document.documentElement.clientWidth");
  if (sideways > 0) {
    page.problem(`the page scrolls sideways by ${sideways} px`);
  }
  await expect(page, "every contrast pair to pass", "!document.querySelector('#pairs .is-low') && document.querySelectorAll('#pairs tr').length > 40");
  // The expected value is chosen here rather than spliced into the page's code.
  const swatch = await page.evaluate(`document.querySelector('[data-swatch="bg"]')?.textContent ?? ""`);
  if (swatch !== (theme === "dark" ? "#0c0f13" : "#f3f4f6")) {
    page.problem("expected swatch values of the emulated theme");
  }
  await fullShot(page, `system-${view}-${theme}`, view);
}

async function moments(page) {
  await page.view("laptop", "light");
  await fresh(page, "moment 1, the tray unloads");
  await press(page, '[data-act="m1"]');
  await sleep(2200);
  await expect(page, "the tray to empty and hide", "document.querySelector('[data-m1-queue]').hidden");
  await expect(page, "both messages delivered in the log", "document.querySelectorAll('[data-m1-log] .kq-entry').length === 3 && document.querySelectorAll('[data-m1-log] .kq-st-done').length === 3");
  await page.shot("moment-1-tray", "#m1");

  page.step = "moment 2, the person moves";
  await press(page, '[data-act="m2"]');
  await sleep(700);
  await expect(page, "Khadija in the online section", "document.querySelector('[data-m2-section=\"online\"] [data-m2-row=\"khadija\"]') && document.querySelector('[data-m2-online]').textContent === '3'");
  await page.shot("moment-2-move", "#m2");

  page.step = "moment 3, the request is accepted";
  await press(page, '[data-act="m3"]');
  await sleep(900);
  await expect(page, "a solid live path with the initials", "document.querySelector('[data-m3-line]').classList.contains('is-live') && document.querySelector('[data-m3-initials]').textContent === 'СН'");
  await page.shot("moment-3-accept", "#m3");

  page.step = "moment 4, the dossier";
  await press(page, '[data-act="m4"]');
  await sleep(700);
  await expect(page, "the dossier open and visible", "document.querySelector('#m4').classList.contains('is-open') && getComputedStyle(document.querySelector('#m4-panel')).visibility === 'visible'");
  await page.shot("moment-4-dossier", "#m4");
  await page.key("Escape");
  await sleep(700);
  await expect(page, "Escape to close the dossier", "!document.querySelector('#m4').classList.contains('is-open')");

  page.step = "moment 1 reset";
  await press(page, '[data-act="m1"]');
  await sleep(200);
  await expect(page, "the reset to bring the tray back", "!document.querySelector('[data-m1-queue]').hidden && document.querySelectorAll('[data-m1-item]').length === 2");
}

async function fields(page) {
  await page.view("laptop", "light");
  await fresh(page, "ID field");
  await press(page, '[data-act="id-good"]');
  await expect(page, "a good ID to pass the checksum", "document.querySelector('[data-verdict]').classList.contains('is-good') && !document.querySelector('[data-id-send]').disabled");
  await press(page, '[data-act="id-bad"]');
  await expect(page, "a corrupted ID to fail the checksum", "document.querySelector('[data-verdict]').classList.contains('is-bad') && document.querySelector('[data-id-send]').disabled");
  await press(page, '[data-act="id-own"]');
  await expect(page, "the own ID to be refused", "/собственный/.test(document.querySelector('[data-verdict]').textContent)");
  await press(page, '[data-act="id-clear"]');
  const pasted = await page.evaluate("document.querySelector('[data-demo-id=\"yusuf\"]').textContent");
  await press(page, "#tox-id");
  await page.type(`tox:${pasted.toLowerCase()}`);
  await expect(page, "a pasted ID in any shape to be cleaned, grouped by four and accepted", "/^([0-9A-F]{4} ){18}[0-9A-F]{4}$/.test(document.querySelector('#tox-id').value) && document.querySelector('[data-verdict]').classList.contains('is-good')");
  await page.shot("field-id", "#fields");

  page.step = "command line";
  await press(page, "#cmd");
  await page.type("юс");
  await sleep(100);
  await expect(page, "a name to filter the list", "/Юсуф Каримов/.test(document.querySelector('#cmd-results').textContent)");
  await page.evaluate("(() => { const f = document.querySelector('#cmd'); f.value = ''; f.dispatchEvent(new Event('input')); return true; })()");
  await page.type("ABCDEF0123456789");
  await sleep(100);
  await expect(page, "an ID to offer a new contact", "/Новый контакт с этим ID/.test(document.querySelector('#cmd-results').textContent)");

  page.step = "composer";
  await press(page, '[data-seg="to"] [data-value="yusuf"]');
  await sleep(400);
  await expect(page, "the hint to say it leaves at once", "/Уйдёт сразу/.test(document.querySelector('[data-hint]').textContent)");
  await press(page, "#compose");
  await page.type("1\n2\n3\n4\n5\n6\n7");
  await sleep(100);
  await expect(page, "the field to stop growing at five lines", "(() => { const f = document.querySelector('#compose'); const s = getComputedStyle(f); return f.clientHeight <= parseFloat(s.lineHeight) * 5 + parseFloat(s.paddingTop) + parseFloat(s.paddingBottom) + 1 && s.overflowY === 'auto'; })()");
  await expect(page, "send to be enabled", "!document.querySelector('[data-send]').disabled");
}

async function overlays(page) {
  await page.view("laptop", "light");
  await fresh(page, "sheet");
  await press(page, '[data-act="sheet"]');
  await sleep(500);
  await expect(page, "the sheet to open", "document.querySelector('#phone').classList.contains('is-sheet')");
  await page.shot("sheet-open", "#phone");
  await page.key("Escape");
  await sleep(500);
  await expect(page, "Escape to close the sheet", "!document.querySelector('#phone').classList.contains('is-sheet')");

  page.step = "sheet drag";
  await press(page, '[data-act="sheet"]');
  await sleep(500);
  const grip = await page.target(null, ".kq-sheet-grip");
  if (grip) {
    await page.mouse("mouseMoved", grip.x, grip.y, false);
    await page.mouse("mousePressed", grip.x, grip.y, true);
    for (let i = 1; i <= 8; i += 1) {
      await page.mouse("mouseMoved", grip.x, grip.y + i * 20, true);
      await sleep(16);
    }
    await page.mouse("mouseReleased", grip.x, grip.y + 160, false);
    await sleep(500);
    await expect(page, "a drag down to close the sheet", "!document.querySelector('#phone').classList.contains('is-sheet')");
  }

  page.step = "dialog";
  await press(page, '[data-act="dialog"]');
  await sleep(400);
  await expect(page, "the dialog to open", "document.querySelector('#dialog').open");
  await page.shot("dialog");
  await page.click(null, '#dialog button[value="block"]');
  await sleep(300);
  await expect(page, "the dialog result to be reported", "!document.querySelector('#dialog').open && /заблокирован/.test(document.querySelector('[data-dialog-result]').textContent)");

  page.step = "toast with undo";
  await press(page, '[data-act="toast-decline"]');
  await sleep(400);
  await expect(page, "a toast with an undo", "/Отменить/.test(document.querySelector('#toasts').textContent)");
  await page.shot("toast");
  await page.click(null, "#toasts .kq-toast button");
  await sleep(500);
  await expect(page, "the undo confirmation", "/снова ждёт/.test(document.querySelector('#toasts').textContent)");

  page.step = "busy button";
  await press(page, '[data-act="busy"]');
  await sleep(200);
  await expect(page, "the busy state", "document.querySelector('[data-act=\"busy\"]').classList.contains('is-busy')");
  await sleep(1600);
  await expect(page, "the error in place after the busy state", "!document.querySelector('[data-busy-error]').hidden");
}

async function switches(page) {
  await page.view("laptop", "light");
  await fresh(page, "theme switch");
  await press(page, '.sg-head [data-seg="theme"] [data-value="dark"]');
  await sleep(300);
  await expect(page, "the dark theme on request", "document.documentElement.getAttribute('data-theme') === 'dark' && document.querySelector('[data-swatch=\"bg\"]').textContent === '#0c0f13'");
  await page.shot("theme-dark-on-light-system");
  await press(page, '.sg-head [data-seg="theme"] [data-value="system"]');
  await sleep(300);
  await expect(page, "the system theme back", "!document.documentElement.hasAttribute('data-theme')");

  page.step = "pills and density";
  await press(page, '[data-pill="transit"]');
  await expect(page, "the transit pill to filter the list", "document.querySelectorAll('#air-list .kq-row').length === 1 && /Амина/.test(document.querySelector('#air-list').textContent)");
  await press(page, '[data-pill="transit"]');
  await press(page, '[data-seg="density"] [data-value="compact"]');
  await expect(page, "the compact density", "document.querySelector('#air-list').classList.contains('is-compact')");
  await page.shot("list-compact", "#list");
}

async function reducedMotion(page) {
  await page.view("laptop", "light", true);
  await fresh(page, "reduced motion");
  await expect(page, "the calm class from the system setting", "document.documentElement.classList.contains('is-calm')");
  await expect(page, "the waiting dot to stand still", "getComputedStyle(document.querySelector('.kq-pulse')).animationName === 'none'");
  await expect(page, "durations capped at 120 ms", "getComputedStyle(document.documentElement).getPropertyValue('--kq-dur-reveal').trim() === '120ms'");
  await press(page, '[data-act="m1"]');
  await sleep(1200);
  await expect(page, "moment 1 to finish quickly without movement", "document.querySelector('[data-m1-queue]').hidden");
  await press(page, '[data-act="m4"]');
  await sleep(300);
  await expect(page, "the dossier to fade in", "getComputedStyle(document.querySelector('#m4-panel')).opacity === '1'");
  await page.shot("reduced-motion-moments", "#motion");
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  mkdirSync(args.out, { recursive: true });
  const chrome = launchChrome(args.chrome);
  let cdp = null;
  let page = null;
  try {
    cdp = await Cdp.connect(await chrome.endpoint);
    page = await Page.open(cdp, args);
    for (const [view, theme] of OVERVIEW) {
      await overview(page, view, theme);
    }
    await moments(page);
    await fields(page);
    await overlays(page);
    await switches(page);
    await reducedMotion(page);
  }
  finally {
    if (cdp) {
      cdp.close();
    }
    chrome.stop();
  }
  console.log(`screenshots: ${page.shots.length} in ${args.out}`);
  if (page.problems.length) {
    page.problems.forEach((line) => console.log(line));
    console.error(`smoke: ${page.problems.length} problems`);
    return 1;
  }
  console.log("smoke: no problems");
  return 0;
}

main().then((code) => {
  process.exitCode = code;
}, (err) => {
  console.error(err.stack || String(err));
  process.exitCode = 2;
});
