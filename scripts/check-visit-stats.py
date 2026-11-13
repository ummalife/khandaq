#!/usr/bin/env python3
"""Hold khandaq.org's visit statistics to the consent the privacy policy promises.

KHANDAQ (visit statistics, 2026-11-13). The site counts visits with Yandex Metrica and Google
Analytics only after the visitor accepts: web/assets/visit-stats.js asks, and it alone loads the
counters (privacy policy, "This website"). That promise breaks quietly, with nothing failing:

  1. a counter loaded any other way, such as the snippet either service hands out pasted into a
     page, runs before anyone is asked. So no page and no other file of the site names the counters'
     hosts; only the loader does.
  2. every content page loads the loader, so the notice is there to answer and to withdraw from,
     and both privacy policies carry the section the notice links to and the button that brings the
     notice back. The utility pages stay without it, on purpose: the 404 page (served at any unknown
     address, mostly to scanners), the downloads mirror (a plain list of signed files) and the search
     engines' ownership files, which must stay byte for byte as issued.
  3. the header policy lets the counters reach their own hosts and nothing around them: default-src
     stays 'none', so no frames (Metrica's advertising cookie matching); no wildcard or bare scheme
     that would let in any host; no 'strict-dynamic', under which a counter's script could load any
     other; and every host of Yandex, Google and their advertising and cookie matching partners is
     one of STATS_SOURCES below, directive by directive (scripts/generate-security-headers.py).
  4. the loader turns off in so many words what the policy says is off: Webvisor and the click map
     (an option missing from Metrica's init is taken from the counter's settings in Metrica), Google
     signals, ad personalisation and Google's advertising consent.
  5. the loader asks first. It runs here under Node against a stand-in page, and must load nothing
     with no answer, a decline, an unknown stored value, storage turned off or Escape; it must load
     both counters after Accept, so this part cannot pass by loading nothing at all; and a decline,
     a decline in another tab and a return from the back/forward cache after one must stop them,
     delete their cookies and storage on this site and reload.

    scripts/check-visit-stats.py
"""
from __future__ import annotations

import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WEB = ROOT / "web"
LOADER = WEB / "assets" / "visit-stats.js"
HEADERS = ROOT / "infra" / "nginx" / "khandaq-security-headers.conf"

CONTENT_PAGES = ("index.html", "ru/index.html", "changelog.html", "ru/changelog.html",
                 "privacy.html", "ru/privacy.html")
POLICIES = {"privacy.html": "/privacy.html#website", "ru/privacy.html": "/ru/privacy.html#website"}

COUNTER_HOSTS = re.compile(r"mc\.yandex\.|yandex\.ru/an/|googletagmanager\.com|google-analytics\.com|"
                           r"analytics\.google\.com", re.I)
LOADER_TAG = re.compile(r'<script\s+src="/assets/visit-stats\.js(?:\?v=[0-9a-f]+)?"\s+defer></script>')
TEXT_SUFFIXES = (".html", ".js", ".mjs", ".css", ".json", ".txt", ".xml", ".svg")

# What the counters may reach once they run, directive by directive: their tags, their hits and their
# pixels. Written out here on purpose rather than imported from the generator, so that widening the
# policy takes two edits, this one made with the privacy policy's text in mind.
STATS_SOURCES = {
    "script-src": {"https://mc.yandex.ru", "https://www.googletagmanager.com"},
    "img-src": {"https://mc.yandex.ru", "https://mc.yandex.com", "https://*.google-analytics.com",
                "https://*.googletagmanager.com"},
    "connect-src": {"https://mc.yandex.ru", "https://mc.yandex.com", "https://*.google-analytics.com",
                    "https://*.analytics.google.com", "https://*.googletagmanager.com"},
}
# Yandex (yandex.*, ya.ru, yastatic.net, Yango, AdFox), Google (google.*, google-analytics.com,
# googletagmanager.com, gstatic.com, DoubleClick) and the operators Metrica matches cookies with.
STATS_FAMILY = re.compile(r"yandex|(?:^|[/.])ya\.ru\b|yastatic|yango|adfox|google|gstatic|doubleclick|"
                          r"beeline", re.I)
ANY_HOST_SCHEMES = {"http:", "https:", "ws:", "wss:"}

# Section 5: the loader against a stand-in page, under Node. Enough of the DOM for what visit-stats.js
# touches; a new call there that this stand-in lacks fails loudly here, and the stand-in grows with it.
LOADER_PROBE = r"""
'use strict';
const fs = require('fs');
const vm = require('vm');

const SOURCE = fs.readFileSync(process.argv[2], 'utf8');
const KEY = 'kq-visit-stats';
const METRIKA_TAG = 'https://mc.yandex.ru/metrika/tag.js';
const GA_TAG = 'https://www.googletagmanager.com/gtag/js';
const failures = [];
const thrown = new Map();
let cases = 0;

function storage(items) {
  const map = new Map(Object.entries(items || {}));
  return {
    map,
    get length() { return map.size; },
    key(i) { const keys = Array.from(map.keys()); return i < keys.length ? keys[i] : null; },
    getItem(key) { return map.has(key) ? map.get(key) : null; },
    setItem(key, value) { map.set(key, String(value)); },
    removeItem(key) { map.delete(key); },
  };
}

function page(options) {
  const state = { scripts: [], reloads: 0, listeners: {}, timers: [] };
  const local = storage(options.local);
  const session = storage({});
  const cookies = new Map(Object.entries(options.cookies || {}));

  function on(table, type, fn) {
    (table[type] = table[type] || []).push(fn);
  }

  function element(tag) {
    const classes = new Set();
    const listeners = {};
    const node = {
      tagName: tag.toUpperCase(), parent: null, children: [], hidden: false, inert: false,
      style: {}, attributes: {}, textContent: '', className: '', offsetHeight: 120,
      classList: {
        contains: (name) => classes.has(name),
        add: (name) => classes.add(name),
        remove: (name) => classes.delete(name),
        toggle(name, force) {
          const on = force === undefined ? !classes.has(name) : Boolean(force);
          if (on) { classes.add(name); } else { classes.delete(name); }
          return on;
        },
      },
      setAttribute(name, value) { node.attributes[name] = String(value); },
      appendChild(child) { child.parent = node; node.children.push(child); return child; },
      addEventListener(type, fn) { on(listeners, type, fn); },
      contains(other) {
        for (let at = other; at; at = at.parent) { if (at === node) { return true; } }
        return false;
      },
      focus() { document.activeElement = node; },
      fire(type, extra) {
        const event = Object.assign({ type, target: node, currentTarget: node }, extra || {});
        (listeners[type] || []).forEach((fn) => fn.call(node, event));
      },
    };
    return node;
  }

  const html = element('html');
  html.lang = 'en';
  const head = element('head');
  const body = element('body');
  html.appendChild(head);
  html.appendChild(body);
  const appendToHead = head.appendChild;
  head.appendChild = (child) => {
    if (child.tagName === 'SCRIPT') { state.scripts.push(String(child.src)); }
    return appendToHead(child);
  };

  const documentListeners = {};
  const document = {
    readyState: 'complete', documentElement: html, head, body, activeElement: body, referrer: '',
    createElement: element,
    createTextNode: (text) => ({ nodeType: 3, textContent: text, parent: null }),
    querySelector: () => null,
    querySelectorAll: () => [],
    addEventListener(type, fn) { on(documentListeners, type, fn); },
    get cookie() { return Array.from(cookies, ([name, value]) => name + '=' + value).join('; '); },
    set cookie(line) {
      const parts = line.split(';');
      const name = parts[0].split('=')[0].trim();
      if (/max-age=0/i.test(parts.slice(1).join(';'))) { cookies.delete(name); }
      else { cookies.set(name, parts[0].slice(parts[0].indexOf('=') + 1)); }
    },
  };

  const window = {
    document,
    sessionStorage: session,
    location: { href: 'https://khandaq.org/', hostname: 'khandaq.org', reload() { state.reloads += 1; } },
    navigator: { userAgent: 'probe' },
    addEventListener(type, fn) { on(state.listeners, type, fn); },
    getComputedStyle: () => ({ display: 'block' }),
    setTimeout(fn) { state.timers.push(fn); return state.timers.length; },
  };
  Object.defineProperty(window, 'localStorage', {
    get() {
      if (options.storageOff) { throw new Error('SecurityError: storage is turned off'); }
      return local;
    },
  });
  window.window = window;
  vm.createContext(window);
  vm.runInContext(SOURCE, window, { filename: 'visit-stats.js' });

  function find(node, test) {
    if (test(node)) { return node; }
    for (const child of node.children || []) {
      const hit = find(child, test);
      if (hit) { return hit; }
    }
    return null;
  }
  const notice = find(body, (node) => node.tagName === 'SECTION');
  return {
    state, window, local, cookies, notice,
    button: (label) => notice && find(notice, (node) => node.tagName === 'BUTTON' && node.textContent === label),
    fire(type, extra) {
      (state.listeners[type] || []).forEach((fn) => fn(Object.assign({ type }, extra || {})));
    },
  };
}

function check(name, options, act, expect) {
  cases += 1;
  let view;
  try {
    view = page(options);
    if (act) { act(view); }
  } catch (error) {
    const message = String(error);
    thrown.set(message, (thrown.get(message) || []).concat(name));
    return;
  }
  const scripts = view.state.scripts;
  const loaded = scripts.some((src) => src.indexOf(METRIKA_TAG) === 0) + scripts.some((src) => src.indexOf(GA_TAG) === 0);
  const facts = {
    scripts: scripts.length,
    both: loaded === 2 && scripts.length === 2,
    ym: typeof view.window.ym,
    gtag: typeof view.window.gtag,
    shown: Boolean(view.notice) && !view.notice.hidden,
    stored: options.storageOff ? null : view.local.getItem(KEY),
    reloads: view.state.reloads,
    stopped: view.window['disableYaCounter108575437'] === true && view.window['ga-disable-G-XQZD21QBB2'] === true,
    cookies: Array.from(view.cookies.keys()).sort().join(','),
    keys: Array.from(view.local.map.keys()).filter((key) => key !== KEY).sort().join(','),
  };
  for (const [fact, wanted] of Object.entries(expect)) {
    if (facts[fact] !== wanted) {
      failures.push(name + ': ' + fact + ' is ' + JSON.stringify(facts[fact]) + ', expected ' + JSON.stringify(wanted));
    }
  }
}

const NOTHING = { scripts: 0, ym: 'undefined', gtag: 'undefined', reloads: 0 };
const counters = { _ym_uid: '1', _ym_d: '2', _ga: 'GA1.1.1', _ga_XQZD21QBB2: 'GS1', site: 'kept' };
const keys = { _ym_retryReqs: '{}', 'kq-theme': 'dark' };
const decline = (view) => view.button('Decline').fire('click');
const elsewhere = (view) => view.local.setItem(KEY, 'denied');

check('no answer yet', {}, null, Object.assign({ shown: true, stored: null }, NOTHING));
check('declined earlier', { local: { [KEY]: 'denied' } }, null, Object.assign({ shown: false }, NOTHING));
check('an unknown stored value', { local: { [KEY]: 'yes' } }, null, Object.assign({ shown: true }, NOTHING));
check('storage turned off', { storageOff: true }, null, Object.assign({ shown: true }, NOTHING));
check('Escape without an answer', {}, (view) => view.notice.fire('keydown', { key: 'Escape' }),
  Object.assign({ shown: false, stored: null }, NOTHING));
check('Decline', {}, decline, Object.assign({ shown: false, stored: 'denied' }, NOTHING));
check('accepted earlier', { local: { [KEY]: 'granted' } }, null, { both: true, shown: false });
check('Accept', {}, (view) => view.button('Accept').fire('click'), { both: true, shown: false, stored: 'granted' });
check('Decline after accepting', { local: Object.assign({ [KEY]: 'granted' }, keys), cookies: counters }, decline,
  { stored: 'denied', reloads: 1, stopped: true, cookies: 'site', keys: 'kq-theme' });
check('declined in another tab', { local: Object.assign({ [KEY]: 'granted' }, keys), cookies: counters },
  (view) => { elsewhere(view); view.fire('storage', { key: KEY }); },
  { reloads: 1, stopped: true, cookies: 'site', keys: 'kq-theme' });
check('back from the back/forward cache after a decline', { local: { [KEY]: 'granted' } },
  (view) => { elsewhere(view); view.fire('pageshow', { persisted: true }); }, { reloads: 1, stopped: true });

thrown.forEach((names, message) => {
  failures.push('it threw ' + message + ' (' + names.join('; ') + '). If it uses that on purpose now, ' +
    'the stand-in page in scripts/check-visit-stats.py needs it too');
});
if (failures.length) {
  failures.forEach((line) => console.log(line));
  process.exit(1);
}
console.log(cases);
"""


def csp_directives(policy: str) -> dict[str, list[str]]:
    directives: dict[str, list[str]] = {}
    for part in policy.split(";"):
        tokens = part.split()
        if tokens:
            directives[tokens[0].lower()] = tokens[1:]
    return directives


def csp_failures(policy: str) -> list[str]:
    failures: list[str] = []
    directives = csp_directives(policy)
    if directives.get("default-src") != ["'none'"]:
        failures.append(f"the CSP's default-src is {' '.join(directives.get('default-src', [])) or 'missing'}, "
                        f"not 'none': whatever it allows reaches frames and every fetch without a directive")
    for directive in ("frame-src", "child-src"):
        if directive in directives:
            failures.append(f"the CSP sets {directive}: Metrica's advertising frame would be allowed")
    for directive, tokens in directives.items():
        allowed = STATS_SOURCES.get(directive, set())
        for token in tokens:
            lowered = token.lower()
            if lowered.startswith("'"):
                # Keywords, hashes and nonces name no host; one of them widens what scripts may load.
                if lowered == "'strict-dynamic'":
                    failures.append(f"the CSP's {directive} carries 'strict-dynamic': a counter's script "
                                    f"could then load any other, wherever it comes from")
                continue
            host = re.match(r"(?:[a-z][a-z0-9+.-]*://)?([^/:]*)", lowered).group(1)
            if lowered in ANY_HOST_SCHEMES or re.fullmatch(r"\*(?:\.[a-z0-9-]+)?", host):
                failures.append(f"the CSP's {directive} allows {token}, which lets in any host, the counters' "
                                f"advertising partners included")
            elif STATS_FAMILY.search(token) and token not in allowed:
                needed = ", ".join(sorted(allowed)) or "none of these hosts"
                failures.append(f"the CSP's {directive} allows {token}; the counters need {needed} there "
                                f"(STATS_SOURCES)")
    return failures


def option_failures(loader: str) -> list[str]:
    failures: list[str] = []
    init = re.search(r"ym\(METRIKA_ID,\s*'init',\s*\{(.*?)\}\);", loader, re.S)
    if not init:
        failures.append("visit-stats.js has no ym(METRIKA_ID, 'init', {...}) call this check can read")
    else:
        for option in ("webvisor", "clickmap"):
            values = re.findall(rf"\b{option}:\s*(\w+)", init.group(1))
            if values != ["false"]:
                failures.append(f"Metrica's init must set {option}: false, once (found {values or 'nothing'}); "
                                f"left out, the counter's settings in Metrica decide")
    for option, value in (("allow_google_signals", "false"), ("allow_ad_personalization_signals", "false"),
                          ("ad_storage", "'denied'"), ("ad_user_data", "'denied'"),
                          ("ad_personalization", "'denied'")):
        if not re.search(rf"\b{option}:\s*{re.escape(value)}", loader):
            failures.append(f"visit-stats.js no longer sets {option}: {value}, which the privacy policy promises")
    return failures


def probe_failures() -> tuple[list[str], int]:
    node = shutil.which("node")
    if node is None:
        return ["node is not installed, so the loader's behaviour went unchecked (CI has it; locally, "
                "install Node.js)"], 0
    try:
        result = subprocess.run([node, "-", str(LOADER)], input=LOADER_PROBE, capture_output=True,
                                text=True, timeout=120, check=False)
    except subprocess.TimeoutExpired:
        return ["the loader probe did not finish within 120 s"], 0
    lines = [line for line in (result.stdout + result.stderr).splitlines() if line.strip()]
    if result.returncode != 0:
        return [f"visit-stats.js on a stand-in page, {line}" for line in lines] or [
            f"the loader probe exited with {result.returncode} and said nothing"], 0
    try:
        return [], int(lines[-1])
    except (IndexError, ValueError):
        return [f"the loader probe passed but did not report its cases: {lines!r}"], 0


def main() -> int:
    failures: list[str] = []
    if not LOADER.is_file():
        print(f"FAIL: {LOADER.relative_to(ROOT)} does not exist", file=sys.stderr)
        return 1
    loader = LOADER.read_text(encoding="utf-8")

    # 1. Only the loader names the counters' hosts.
    scanned = 0
    for path in sorted(WEB.rglob("*")):
        if path == LOADER or not path.is_file() or path.suffix not in TEXT_SUFFIXES:
            continue
        scanned += 1
        text = path.read_text(encoding="utf-8", errors="replace")
        for m in COUNTER_HOSTS.finditer(text):
            line = text.count("\n", 0, m.start()) + 1
            failures.append(f"{path.relative_to(ROOT)}:{line}: names {m.group(0)}; a counter loaded from "
                            f"here would run before the visitor is asked. Only visit-stats.js loads them.")
    if not COUNTER_HOSTS.search(loader):
        failures.append(f"{LOADER.relative_to(ROOT)} names none of the counters' hosts: it changed shape, and "
                        f"this check would pass vacuously")

    # 2. The content pages ask, the utility pages do not, and the policies answer.
    pages = sorted(WEB.rglob("*.html"))
    for page in pages:
        name = page.relative_to(WEB).as_posix()
        html = page.read_text(encoding="utf-8")
        linked = len(LOADER_TAG.findall(html))
        mentioned = "visit-stats.js" in html
        if name in CONTENT_PAGES:
            if linked != 1:
                failures.append(f"web/{name} must load /assets/visit-stats.js exactly once, with defer "
                                f"(found {linked})")
        elif mentioned:
            failures.append(f"web/{name} loads visit-stats.js, but it is a utility page that stays "
                            f"without statistics (see this script's docstring)")
    missing = [name for name in CONTENT_PAGES if not (WEB / name).is_file()]
    if missing:
        failures.append(f"content pages missing from web/: {', '.join(missing)}")

    for name, href in POLICIES.items():
        page = WEB / name
        html = page.read_text(encoding="utf-8") if page.is_file() else ""
        if 'id="website"' not in html:
            failures.append(f"web/{name} has no section id=\"website\", which the notice links to")
        if not re.search(r"<button\b[^>]*\bdata-visit-stats-open\b[^>]*\bhidden\b", html):
            failures.append(f"web/{name} has no hidden button marked data-visit-stats-open, so a "
                            f"visitor could not bring the notice back to withdraw")
        if f"'{href}'" not in loader:
            failures.append(f"visit-stats.js does not link the notice to {href}")

    # 3. The counters reach their own hosts and nothing around them.
    headers = HEADERS.read_text(encoding="utf-8") if HEADERS.is_file() else ""
    csp = re.search(r'add_header Content-Security-Policy "([^"]*)"', headers)
    if not csp:
        failures.append(f"{HEADERS.relative_to(ROOT)} carries no Content-Security-Policy")
    else:
        failures.extend(csp_failures(csp.group(1)))

    # 4. What the policy says is off is off in the loader itself.
    failures.extend(option_failures(loader))

    # 5. The loader asks before it loads anything, and a decline stops it.
    probe, cases = probe_failures()
    failures.extend(probe)

    if failures:
        print("FAIL: the visit statistics no longer match what the privacy policy promises:", file=sys.stderr)
        for line in failures:
            print(f"  - {line}", file=sys.stderr)
        return 1
    print(f"ok: only visit-stats.js names the counters ({scanned} other files scanned); "
          f"{len(CONTENT_PAGES)} content pages load it, {len(pages) - len(CONTENT_PAGES)} utility pages do "
          f"not; both policies carry #website and the button; the CSP lets the counters reach only their "
          f"own hosts, with no frames; Webvisor, the click map and Google's advertising are off; the loader "
          f"passed {cases} cases on a stand-in page")
    return 0


if __name__ == "__main__":
    sys.exit(main())
