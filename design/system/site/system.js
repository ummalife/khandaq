// Behaviour of the design system page for direction B (Air).
//
// Token readouts are computed from window.KQ_TOKENS, the same data build.py turns into the client
// files, so the page cannot drift from the apps. Every component is live: the theme and motion
// switches, the component states and the four key moments of the direction.

(function designSystemPage() {
  "use strict";

  const TOKENS = window.KQ_TOKENS;
  const root = document.documentElement;
  const reducedQuery = window.matchMedia("(prefers-reduced-motion: reduce)");
  const darkQuery = window.matchMedia("(prefers-color-scheme: dark)");
  const EASE_OUT = `cubic-bezier(${TOKENS.motion.easing.out.join(", ")})`;
  const SCHEMES = ["light", "dark"];
  // A token as the page resolves it right now, theme included, for keyframes built in script.
  const token = (name) => getComputedStyle(root).getPropertyValue(`--kq-${name}`).trim();

  let hostTheme = root.getAttribute("data-host-theme") || "";

  const $ = (selector, scope = document) => scope.querySelector(selector);
  const $$ = (selector, scope = document) => Array.from(scope.querySelectorAll(selector));

  function esc(text) {
    return String(text).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  }

  function icon(name) {
    return `<svg class="ic" aria-hidden="true"><use href="#i-${name}"/></svg>`;
  }

  function plural(n, one, few, many) {
    const tens = n % 100;
    const units = n % 10;
    if (tens >= 11 && tens <= 14) {
      return many;
    }
    if (units === 1) {
      return one;
    }
    if (units >= 2 && units <= 4) {
      return few;
    }
    return many;
  }

  function decimal(value, digits) {
    return value.toFixed(digits).replace(".", ",");
  }

  // ---- stored choices: a blocked store only means the choice lasts for this visit -------------------

  function readChoice(key, fallback) {
    try {
      return localStorage.getItem(key) || fallback;
    }
    catch (err) {
      return fallback;
    }
  }

  function saveChoice(key, value) {
    try {
      localStorage.setItem(key, value);
    }
    catch (err) {
      // Private windows and blocked site data refuse storage; the page still works for this visit.
    }
  }

  // ---- motion ------------------------------------------------------------------------------------------

  function isCalm() {
    return root.classList.contains("is-calm");
  }

  function duration(name) {
    const value = parseFloat(getComputedStyle(root).getPropertyValue(`--kq-dur-${name}`));
    return Number.isFinite(value) ? value : 0;
  }

  function wait(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  // Runs a Web Animation and resolves when it ends or is cancelled; the caller sets the final state.
  function play(el, frames, options) {
    if (!el || typeof el.animate !== "function") {
      return Promise.resolve();
    }
    const animation = el.animate(frames, { easing: EASE_OUT, ...options });
    return animation.finished.then(() => undefined, () => undefined);
  }

  // Swaps content with a cross fade, so a line of text changes without a jump.
  async function crossFade(el, update) {
    const half = duration("state") / 2;
    await play(el, [{ opacity: 1 }, { opacity: 0 }], { duration: half, fill: "forwards" });
    update();
    await play(el, [{ opacity: 0 }, { opacity: 1 }], { duration: half });
    el.getAnimations().forEach((a) => a.cancel());
  }

  // ---- colour maths, the same as build.py ---------------------------------------------------------------

  function rgba(hex) {
    const d = hex.slice(1);
    return [0, 2, 4].map((i) => parseInt(d.slice(i, i + 2), 16)).concat(d.length === 8 ? parseInt(d.slice(6, 8), 16) : 255);
  }

  function channel(c) {
    const v = c / 255;
    return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
  }

  function luminance(hex) {
    const [r, g, b] = rgba(hex);
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
  }

  function contrast(fg, bg) {
    const a = luminance(fg);
    const b = luminance(bg);
    return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
  }

  function composite(top, bottom) {
    const [r1, g1, b1, a1] = rgba(top);
    const [r2, g2, b2] = rgba(bottom);
    const alpha = a1 / 255;
    return "#" + [[r1, r2], [g1, g2], [b1, b2]]
      .map(([c1, c2]) => Math.round(c1 * alpha + c2 * (1 - alpha)).toString(16).padStart(2, "0"))
      .join("");
  }

  // A translucent ground is tried over black and over white; a translucent foreground is laid over it.
  function worstContrast(fg, bg) {
    const grounds = rgba(bg)[3] === 255 ? [bg] : [composite(bg, "#000000"), composite(bg, "#ffffff")];
    return Math.min(...grounds.map((ground) => contrast(rgba(fg)[3] === 255 ? fg : composite(fg, ground), ground)));
  }

  // ---- theme -------------------------------------------------------------------------------------------

  function currentScheme() {
    const stamp = root.getAttribute("data-theme");
    if (stamp === "light" || stamp === "dark") {
      return stamp;
    }
    return darkQuery.matches ? "dark" : "light";
  }

  function applyTheme(choice) {
    if (choice === "light" || choice === "dark") {
      root.setAttribute("data-theme", choice);
    }
    else if (hostTheme) {
      root.setAttribute("data-theme", hostTheme);
    }
    else {
      root.removeAttribute("data-theme");
    }
    saveChoice("kq-system-theme", choice);
    syncSegment("theme", choice);
    renderSwatchValues();
  }

  function applyMotion(choice) {
    const calm = choice === "calm" || (choice === "system" && reducedQuery.matches);
    root.classList.toggle("is-calm", calm);
    saveChoice("kq-system-motion", choice);
    syncSegment("motion", choice);
  }

  // The artifact host may restamp the theme while the page is open; "as in the system" follows it.
  new MutationObserver(() => {
    if (readChoice("kq-system-theme", "system") === "system") {
      hostTheme = root.getAttribute("data-theme") || "";
    }
    renderSwatchValues();
  }).observe(root, { attributes: true, attributeFilter: ["data-theme"] });

  darkQuery.addEventListener("change", renderSwatchValues);
  reducedQuery.addEventListener("change", () => applyMotion(readChoice("kq-system-motion", "system")));

  // ---- segments ----------------------------------------------------------------------------------------

  const segmentHandlers = {
    theme: applyTheme,
    motion: applyMotion,
    to: (value) => setComposeTarget(value),
    density: (value) => {
      $("#air-list").classList.toggle("is-compact", value === "compact");
      syncSegment("density", value);
    },
  };

  function syncSegment(name, value) {
    $$(`[data-seg="${name}"] button`).forEach((button) => {
      button.setAttribute("aria-pressed", String(button.dataset.value === value));
    });
  }

  document.addEventListener("click", (event) => {
    const button = event.target.closest("[data-seg] button");
    if (!button) {
      return;
    }
    const name = button.closest("[data-seg]").dataset.seg;
    const handler = segmentHandlers[name];
    if (handler) {
      handler(button.dataset.value);
    }
  });

  // ---- colour ------------------------------------------------------------------------------------------

  const COLOUR_GROUPS = [
    ["Основа", ["bg", "surface", "raised", "sunken", "selected", "line", "line-strong"]],
    ["Чернила", ["ink", "ink-2", "ink-3", "on-ink", "ink-hover", "ink-pressed", "ink-badge", "switch-on"]],
    ["Сигналы", ["live", "live-soft", "live-cell", "transit", "transit-soft", "transit-tray", "danger", "danger-soft", "focus"]],
    ["Тона авторов в группах", ["tone-1", "tone-2", "tone-3", "tone-4", "tone-5", "tone-6"]],
    ["Медиа, звонки, наложения", ["fixed-white", "fixed-black", "live-fill", "danger-fill", "media-control", "media-shade", "lock-top", "lock-bottom", "scrim", "shadow"]],
  ];

  const COLOUR_USE = {
    bg: "фон приложения",
    surface: "главная поверхность: список, переписка",
    raised: "кнопки, шторки, всплывающие панели",
    sunken: "поля, плитки, командная строка",
    selected: "выбранная строка, свои сообщения",
    line: "разделители",
    "line-strong": "рамки кнопок и полей",
    ink: "основной текст, главная кнопка",
    "ink-2": "второстепенный текст",
    "ink-3": "служебный текст: время, подписи",
    "on-ink": "текст на чернильной кнопке",
    "ink-hover": "чернильная кнопка под указателем",
    "ink-pressed": "чернильная кнопка в момент нажатия",
    "ink-badge": "подложка счётчика в нажатом фильтре",
    "switch-on": "дорожка включённого переключателя под белой ручкой",
    live: "в сети сейчас, уйдёт сразу",
    "live-soft": "подложка под зелёным",
    "live-cell": "час, когда собеседник был в сети, в полосе дня",
    transit: "в пути",
    "transit-soft": "подложка под янтарным",
    "transit-tray": "фон лотка «В пути»",
    danger: "сломалось, заблокировано, не отправлено",
    "danger-soft": "подложка под красным",
    focus: "только кольцо фокуса с клавиатуры",
    "fixed-white": "текст и иконки на медиа и в звонке",
    "fixed-black": "фон видео",
    "live-fill": "кнопка «Ответить» с белой иконкой",
    "danger-fill": "кнопка «Отклонить» с белой иконкой",
    "media-control": "полупрозрачная кнопка поверх медиа",
    "media-shade": "баннер в приложении, тень поверх медиа",
    "lock-top": "экран блокировки, верх",
    "lock-bottom": "экран блокировки, низ",
    scrim: "затемнение под шторкой и диалогом",
    shadow: "тень только у всплывающего: уведомление, окно, шторка",
  };

  function colourGroups() {
    const listed = new Set(COLOUR_GROUPS.flatMap(([, names]) => names));
    const rest = Object.keys(TOKENS.color).filter((name) => !listed.has(name));
    const groups = COLOUR_GROUPS.map(([title, names]) => [title, names.filter((name) => TOKENS.color[name])]);
    return rest.length ? groups.concat([["Прочее", rest]]) : groups;
  }

  function renderSwatches() {
    $("#swatches").innerHTML = colourGroups().map(([title, names]) => `
      <div class="sg-grid" style="gap:var(--kq-space-10)">
        <p class="sg-sub">${esc(title)}</p>
        <div class="sg-swatches">${names.map((name) => `
          <div class="sg-swatch">
            <i style="background:var(--kq-${name})"></i>
            <div><b>${name}</b><span class="kq-mono" data-swatch="${name}"></span><span>${esc(COLOUR_USE[name] || (name.startsWith("tone-") ? "имя автора в группе" : TOKENS.color[name].use))}</span></div>
          </div>`).join("")}
        </div>
      </div>`).join("");
  }

  function renderSwatchValues() {
    const scheme = currentScheme();
    $$("[data-swatch]").forEach((el) => {
      el.textContent = TOKENS.color[el.dataset.swatch][scheme];
    });
  }

  function renderPairs() {
    let total = 0;
    let low = 0;
    const rows = TOKENS.contrast.flatMap((rule) => rule.on.map((ground) => {
      const ratios = SCHEMES.map((scheme) => worstContrast(TOKENS.color[rule.fg][scheme], TOKENS.color[ground][scheme]));
      total += 1;
      if (ratios.some((r) => r < rule.min)) {
        low += 1;
      }
      const cells = ratios.map((r) => `<td><span class="sg-verdict${r < rule.min ? " is-low" : ""}">${decimal(r, 2)}</span></td>`).join("");
      return `<tr>
        <td><span class="sg-sample" style="color:var(--kq-${rule.fg});background:var(--kq-${ground})">Аа 12:41</span></td>
        <td><span class="kq-mono">${rule.fg}</span> <span class="sg-cap">на</span> <span class="kq-mono">${ground}</span></td>
        ${cells}
        <td class="kq-num">${decimal(rule.min, 1)}</td>
      </tr>`;
    }));
    $("#pairs").innerHTML = rows.join("");
    $("#pairs-sum").textContent = low
      ? `Пар: ${total}. Ниже порога: ${low}. Сборка токенов такую палитру не пропустит.`
      : `Пар: ${total}, все проходят порог в обеих темах. Полупрозрачные цвета проверены поверх чёрного и белого.`;
  }

  // ---- type, spacing, sizes ----------------------------------------------------------------------------

  const TYPE_SAMPLES = {
    display: "Новый контакт",
    heading: "В сети сейчас",
    body: "Да, там приют. Тогда выезжаем в 6, я возьму палатку",
    label: "Отправить запрос",
    caption: "Подождёт в пути: Юсуф не в сети. Уйдёт само",
    mono: "7F3A 91C0 5D2E 88B4 0C6F A1D3",
    "mono-entry": "7F3A91C05D2E88B40C6FA1D32E9B47F0",
  };

  function renderType() {
    $("#type-roles").innerHTML = Object.entries(TOKENS.font.role).map(([name, role]) => `
      <div class="sg-type">
        <div class="sg-type-meta"><b>${name}</b><span>${role.weight}, ${role.desk.join("/")} на компьютере, ${role.phone.join("/")} на телефоне, iOS ${role["ios-style"]}</span></div>
        <div class="sg-type-sample" style="font:var(--kq-type-${name})">${esc(TYPE_SAMPLES[name] || name)}</div>
      </div>`).join("");
  }

  const RADIUS_USE = {
    tick: "часы в полосе дня",
    small: "клавиши, скелетон",
    control: "кнопки, плитки",
    field: "поля, строки",
    card: "карточки",
    sheet: "шторки, панели",
    round: "счётчики, метки, переключатели",
  };

  const SIZE_USE = {
    "target-ios": ["цель нажатия на iOS", "pt"],
    "target-android": ["цель нажатия на Android", "dp"],
    "row-phone": ["строка списка на телефоне", "pt"],
    "row-desk": ["строка списка на компьютере", "px"],
    "row-desk-compact": ["плотная строка на компьютере", "px"],
    "list-column": ["колонка списка на компьютере", "px"],
    "text-measure": ["предел ширины переписки", "px"],
    icon: ["иконка", "px"],
    "tile-phone": ["плитка на телефоне", "pt"],
    "tile-desk": ["плитка на компьютере", "px"],
    "tile-compact": ["плитка в плотном списке", "px"],
    control: ["кнопка на компьютере", "px"],
    "control-small": ["кнопка внутри строки", "px"],
    field: ["поле и строка команд на компьютере", "px"],
    chip: ["фильтр и строка выбора на компьютере", "px"],
    badge: ["счётчик, упоминание, клавиша", "px"],
    mark: ["флажок, радиокнопка, отметка доставки", "px"],
    "icon-small": ["иконка в плитке и метке", "px"],
    dot: ["точка состояния", "px"],
    bar: ["полоса проверки ID и загрузки", "px"],
    "time-column": ["колонка времени в переписке", "px"],
    "switch-width": ["переключатель, ширина", "px"],
    "switch-height": ["переключатель, высота", "px"],
    "switch-knob": ["ручка переключателя", "px"],
    "pin-cell": ["ячейка PIN, высота", "px"],
    "id-field": ["поле ID, наименьшая высота", "px"],
    "compose-max": ["поле сообщения, наибольшая высота", "px"],
    dialog: ["окно и уведомление, наибольшая ширина", "px"],
    "grip-width": ["ручка шторки, ширина", "px"],
    "grip-height": ["ручка шторки, высота", "px"],
    "link-mark": ["знак связи, ширина", "px"],
    hours: ["полоса дня, ширина", "px"],
    "hour-cell": ["час в полосе дня", "px"],
    "hour-cell-tall": ["час в досье", "px"],
    "skeleton-line": ["строка скелетона", "px"],
  };

  const STROKE_USE = { hairline: "разделители, края полей и кнопок", strong: "упоминание, текущий час", focus: "кольцо фокуса", icon: "линия значков", mark: "линия связи, точки пути" };
  const ELEVATION_USE = { lift: "строка, пока переезжает", toast: "уведомление", dialog: "окно", sheet: "шторка" };

  function renderGrid() {
    $("#space").innerHTML = TOKENS.space.map((value) => `
      <div class="sg-space-row"><span class="kq-num">${value}</span><i style="width:${value}px"></i></div>`).join("");
    $("#radii").innerHTML = Object.entries(TOKENS.radius).map(([name, value]) => `
      <div class="sg-radius"><i style="border-radius:${value}px"></i><span class="sg-cap">${esc(RADIUS_USE[name] || name)}, ${value}</span></div>`).join("");
    // The list is long, so it runs in two columns that become one on a phone.
    const sizes = Object.entries(TOKENS.size).map(([name, value]) => {
      const [use, unit] = SIZE_USE[name] || [name, "px"];
      return `<tr><td>${esc(use)}</td><td class="kq-num">${value} ${unit}</td></tr>`;
    });
    const half = Math.ceil(sizes.length / 2);
    $("#sizes").innerHTML = `<tbody>${sizes.slice(0, half).join("")}</tbody>`;
    $("#sizes-more").innerHTML = `<tbody>${sizes.slice(half).join("")}</tbody>`;
    const strokes = Object.entries(TOKENS.stroke).map(([name, value]) =>
      `<tr><td>${esc(STROKE_USE[name] || name)}</td><td class="kq-num">${value} px</td></tr>`);
    const shadows = Object.entries(TOKENS.elevation).map(([name, [x, y, blur]]) =>
      `<tr><td>Тень: ${esc(ELEVATION_USE[name] || name)}</td><td class="kq-num">${x}, ${y}, ${blur}</td></tr>`);
    $("#strokes").innerHTML = `<tbody>${strokes.join("")}${shadows.join("")}</tbody>`;
  }

  // ---- motion readouts -----------------------------------------------------------------------------------

  const DURATION_USE = { tap: "отклик на нажатие", state: "смена состояния", layout: "перестройка раскладки", reveal: "раскрытие досье" };

  function renderMotion() {
    $("#durations").innerHTML = Object.keys(DURATION_USE).map((name) => `
      <div class="sg-duration">
        <span class="sg-cap">${DURATION_USE[name]}</span>
        <span class="kq-num">${TOKENS.motion.duration[name]} мс</span>
        <span class="sg-track"><i data-dur="${name}"></i></span>
      </div>`).join("") + `<p class="sg-cap" style="margin:var(--kq-space-4) 0 0">Шаг между элементами одной волны: ${TOKENS.motion.duration.stagger} мс.</p>`;

    const curves = [
      ["out", "Основная: появление, раскрытие, перестройка"],
      ["standard", "Стандартная: изменение на месте"],
    ];
    $("#curves").innerHTML = curves.map(([name, use]) => {
      const [x1, y1, x2, y2] = TOKENS.motion.easing[name];
      const p = (x, y) => `${(8 + x * 104).toFixed(1)} ${(112 - y * 104).toFixed(1)}`;
      return `<figure style="margin:0;display:grid;gap:var(--kq-space-6)">
        <svg class="sg-curve" viewBox="0 0 120 120" role="img" aria-label="Кривая ${name}">
          <line x1="8" y1="112" x2="112" y2="112"/><line x1="8" y1="8" x2="8" y2="112"/><line x1="8" y1="8" x2="112" y2="8"/>
          <path d="M${p(0, 0)} C${p(x1, y1)} ${p(x2, y2)} ${p(1, 1)}"/>
        </svg>
        <figcaption class="sg-cap"><span class="kq-mono">${name}</span> (${TOKENS.motion.easing[name].join(", ")}). ${esc(use)}</figcaption>
      </figure>`;
    }).join("");
  }

  async function runDurations() {
    const dots = $$("[data-dur]");
    dots.forEach((dot) => dot.getAnimations().forEach((a) => a.cancel()));
    await Promise.all(dots.map((dot) => {
      const travel = dot.parentElement.clientWidth - dot.offsetWidth - 10;
      const ms = duration(dot.dataset.dur);
      if (isCalm()) {
        return play(dot, [{ opacity: 0, transform: `translateX(${travel}px)` }, { opacity: 1, transform: `translateX(${travel}px)` }], { duration: ms, fill: "forwards" });
      }
      return play(dot, [{ transform: "translateX(0)" }, { transform: `translateX(${travel}px)` }], { duration: ms, fill: "forwards" });
    }));
    await wait(700);
    dots.forEach((dot) => dot.getAnimations().forEach((a) => a.cancel()));
  }

  // ---- demo IDs with a real checksum ----------------------------------------------------------------------

  const TOX_ID_LENGTH = 76;

  function makePrng(seed) {
    let state = seed >>> 0;
    return function next() {
      state = (state + 0x6d2b79f5) >>> 0;
      let t = state;
      t = Math.imul(t ^ (t >>> 15), t | 1);
      t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  // A Tox ID is a 32-byte public key, a 4-byte nospam and a 2-byte checksum: XOR of the first 36 bytes.
  function toxChecksum(bytes36) {
    const sum = [0, 0];
    bytes36.forEach((b, i) => {
      sum[i % 2] ^= b;
    });
    return sum;
  }

  function toHex(bytes) {
    return bytes.map((b) => b.toString(16).padStart(2, "0")).join("").toUpperCase();
  }

  function makeToxId(seed) {
    const rand = makePrng(seed);
    const body = Array.from({ length: 36 }, () => Math.floor(rand() * 256));
    return toHex(body.concat(toxChecksum(body)));
  }

  function checkToxId(hex) {
    if (hex.length !== TOX_ID_LENGTH) {
      return false;
    }
    const bytes = [];
    for (let i = 0; i < hex.length; i += 2) {
      bytes.push(parseInt(hex.slice(i, i + 2), 16));
    }
    const sum = toxChecksum(bytes.slice(0, 36));
    return sum[0] === bytes[36] && sum[1] === bytes[37];
  }

  // Accepts an ID in any usual shape: spaces, line breaks, a tox: prefix, lower case.
  function normalizeToxInput(raw) {
    return raw.replace(/^\s*tox:/i, "").replace(/[^0-9a-fA-F]/g, "").toUpperCase().slice(0, TOX_ID_LENGTH);
  }

  function groupHex(hex) {
    return (hex.match(/.{1,4}/g) || []).join(" ");
  }

  const IDS = { me: makeToxId(7001), yusuf: makeToxId(101), said: makeToxId(110) };

  function corrupt(id) {
    return id.slice(0, -1) + (id.slice(-1) === "0" ? "1" : "0");
  }

  // ---- ID field with the ruler ------------------------------------------------------------------------------

  const RULER = [
    { label: "ключ", from: 0, to: 64 },
    { label: "антиспам", from: 64, to: 72 },
    { label: "проверка", from: 72, to: 76 },
  ];

  function verdictFor(hex) {
    if (!hex) {
      return { kind: "empty" };
    }
    if (hex.length < TOX_ID_LENGTH) {
      return { kind: "partial", left: TOX_ID_LENGTH - hex.length };
    }
    if (!checkToxId(hex)) {
      return { kind: "bad" };
    }
    if (hex.slice(0, 64) === IDS.me.slice(0, 64)) {
      return { kind: "self" };
    }
    return { kind: "ok" };
  }

  function renderIdField(fromUser) {
    const field = $("#tox-id");
    const hex = normalizeToxInput(field.value);
    const grouped = groupHex(hex);
    if (field.value !== grouped) {
      field.value = grouped;
      if (document.activeElement === field) {
        field.setSelectionRange(grouped.length, grouped.length);
      }
    }
    const v = verdictFor(hex);
    $("[data-ruler]").innerHTML = RULER.map((part) => {
      const filled = Math.max(0, Math.min(hex.length, part.to) - part.from);
      const size = part.to - part.from;
      let state = "";
      if (part.label === "проверка" && hex.length === TOX_ID_LENGTH) {
        state = v.kind === "bad" ? " is-bad" : " is-good";
      }
      return `<span class="kq-ruler-part${state}"><span class="kq-ruler-bar"><i style="transform:scaleX(${(filled / size).toFixed(3)})"></i></span><span class="kq-ruler-label">${part.label}<b>${filled}/${size}</b></span></span>`;
    }).join("");

    const texts = {
      empty: ["", "Пробелы, переносы строк и приставку tox: можно не убирать, строка почистит сама."],
      partial: ["", `Осталось ${v.left} ${plural(v.left || 0, "знак", "знака", "знаков")}.`],
      bad: [" is-bad", "Проверка не сошлась: в ID опечатка или он скопирован не целиком. Скопируйте его ещё раз."],
      self: [" is-bad", "Это ваш собственный ID: его отправляют собеседнику, а не добавляют."],
      ok: [" is-good", "ID в порядке. Запрос уйдёт, как только собеседник будет в сети."],
    };
    const [cls, text] = texts[v.kind];
    const verdict = $("[data-verdict]");
    verdict.className = `kq-verdict${cls}`;
    verdict.textContent = text;

    const send = $("[data-id-send]");
    const why = $("[data-id-why]");
    send.disabled = v.kind !== "ok";
    why.hidden = v.kind === "ok";
    why.textContent = v.kind === "self" ? "Свой ID добавить нельзя." : v.kind === "bad" ? "Сначала исправьте ID." : "Сначала вставьте ID целиком: 76 знаков.";

    if (fromUser && v.kind === "bad" && !isCalm()) {
      field.classList.remove("kq-shake");
      void field.offsetWidth;
      field.classList.add("kq-shake");
    }
  }

  // ---- command line ----------------------------------------------------------------------------------------

  const CMD_ITEMS = [
    { tile: "ЮК", tone: 1, name: "Юсуф Каримов", live: true },
    { tile: "АС", tone: 3, name: "Амина Сафина", live: false },
    { tile: "ОХ", tone: 2, name: "Омар Хасанов", live: true },
    { tile: "ПЭ", tone: 2, name: "Поход на Эльбрус", live: true, group: true },
  ];

  function renderCommand() {
    const raw = $("#cmd").value;
    const compact = raw.replace(/^\s*tox:/i, "").replace(/\s+/g, "");
    const out = $("#cmd-results");
    if (!raw.trim()) {
      out.innerHTML = "";
      return;
    }
    if (/^[0-9a-fA-F]{12,}$/.test(compact)) {
      const hex = normalizeToxInput(compact);
      const v = verdictFor(hex);
      const note = v.kind === "ok" ? "ID в порядке" : v.kind === "partial" ? `не хватает ${v.left} ${plural(v.left, "знака", "знаков", "знаков")}` : v.kind === "self" ? "это ваш ID" : "проверка не сошлась";
      out.innerHTML = `<button type="button" class="kq-row" data-act="cmd-id"><span class="kq-tile kq-tile-pending">${icon("user-plus")}</span><span class="kq-row-main"><span class="kq-row-top"><span class="kq-row-name">Новый контакт с этим ID</span></span><span class="kq-row-bottom"><span class="kq-row-last">${esc(note)}</span></span></span></button>`;
      return;
    }
    const query = raw.trim().toLowerCase();
    const found = CMD_ITEMS.filter((item) => item.name.toLowerCase().includes(query));
    out.innerHTML = found.length
      ? found.map((item) => `<div class="kq-row"><span class="kq-tile${item.group ? " kq-tile-group" : ""}" style="color:var(--kq-tone-${item.tone})">${item.tile}</span><span class="kq-row-main"><span class="kq-row-top"><span class="kq-row-name">${esc(item.name)}</span>${item.live ? '<span class="kq-reach is-live"><i></i>в сети</span>' : '<span class="kq-reach">не в сети</span>'}</span></span></div>`).join("")
      : `<p class="sg-cap" style="margin:0;padding:var(--kq-space-8) var(--kq-space-2)">Ничего не нашлось. Если это ID, вставьте его целиком.</p>`;
  }

  // ---- composer and the delivery hint ------------------------------------------------------------------------

  const COMPOSE_TARGETS = {
    amina: { dot: "is-wait", text: "Подождёт в пути: Амина не в сети. Уйдёт само" },
    yusuf: { dot: "is-live", text: "Уйдёт сразу: Юсуф в сети" },
    group: { dot: "is-live", text: "Сразу получат 4 из 9, остальные, когда появятся в сети" },
  };
  const MESSAGE_LIMIT = 1372;
  let composeTarget = "amina";

  function hintMarkup() {
    const target = COMPOSE_TARGETS[composeTarget];
    const bytes = new TextEncoder().encode($("#compose").value).length;
    const left = MESSAGE_LIMIT - bytes;
    let counter = "";
    if (left < MESSAGE_LIMIT * 0.1) {
      counter = left >= 0
        ? `<span class="kq-num" style="margin-inline-start:auto;color:var(--kq-ink-3)">осталось ${left} ${plural(left, "байт", "байта", "байт")}</span>`
        : `<span class="kq-num" style="margin-inline-start:auto;color:var(--kq-danger)">на ${-left} ${plural(-left, "байт", "байта", "байт")} больше предела</span>`;
    }
    return `<span class="kq-dot ${target.dot}"></span><span>${esc(target.text)}</span>${counter}`;
  }

  function setComposeTarget(value) {
    syncSegment("to", value);
    if (value === composeTarget) {
      return;
    }
    composeTarget = value;
    crossFade($("[data-hint]"), () => {
      $("[data-hint]").innerHTML = hintMarkup();
    });
  }

  function growCompose() {
    const field = $("#compose");
    const style = getComputedStyle(field);
    const line = parseFloat(style.lineHeight) || 21;
    const padding = parseFloat(style.paddingTop) + parseFloat(style.paddingBottom);
    field.style.height = "auto";
    field.style.height = `${Math.min(field.scrollHeight, line * 5 + padding)}px`;
    field.style.overflowY = field.scrollHeight > line * 5 + padding ? "auto" : "hidden";
    $("[data-send]").disabled = !field.value.trim();
    $("[data-hint]").innerHTML = hintMarkup();
  }

  // ---- list ------------------------------------------------------------------------------------------------

  const LIST_SECTIONS = [["live", "В сети сейчас"], ["off", "Не в сети"], ["self", "Для себя"]];

  const LIST = [
    { section: "live", tile: "ЮК", tone: 1, name: "Юсуф Каримов", reach: "в сети", live: true, time: "12:41", last: "И горелку, если твоя на ремонте", unread: 2 },
    { section: "live", tile: "ПЭ", tone: 2, group: true, name: "Поход на Эльбрус", reach: "4 в сети", live: true, time: "12:22", last: "Зайд: Меня возьмите!", unread: 5, mention: true },
    { section: "live", tile: "ОХ", tone: 2, name: "Омар Хасанов", reach: "в сети, узел", live: true, time: "вчера", last: "Голосовое, 0:42" },
    { section: "off", tile: "АС", tone: 3, name: "Амина Сафина", reach: "25 мин", time: "12:31", last: "И напомни, сколько брать воды", mine: "wait", transit: 2 },
    { section: "off", tile: "ХЮ", tone: 4, name: "Хадиджа Юнусова", reach: "3 дн", time: "пн", last: "Спасибо, дошло", mine: "done", muted: true },
    { section: "off", tile: "СН", pending: true, name: "Саид Нуриев", reach: "ждёт ответа", time: "11:02", last: "запрос отправлен" },
    { section: "self", saved: true, name: "Избранное", time: "08:30", last: "На субботу: вода 3 л, фонарик, повербанк" },
  ];

  const PILL_FILTERS = {
    requests: (item) => item.pending,
    transit: (item) => item.transit,
    saved: (item) => item.saved,
  };

  let pillFilter = "";

  function listTile(item) {
    if (item.saved) {
      return `<span class="kq-tile kq-tile-saved">${icon("bookmark")}</span>`;
    }
    if (item.pending) {
      return `<span class="kq-tile kq-tile-pending">${item.tile}</span>`;
    }
    return `<span class="kq-tile${item.group ? " kq-tile-group" : ""}" style="color:var(--kq-tone-${item.tone})">${item.tile}</span>`;
  }

  function listRow(item, current) {
    const status = item.mine === "wait"
      ? `<span class="kq-st kq-st-wait">${icon("clock")}</span>`
      : item.mine === "done" ? `<span class="kq-st kq-st-done">${icon("check-double")}</span>` : "";
    const flags = [];
    if (item.transit) {
      flags.push(`<span class="kq-transit" aria-label="${item.transit} в пути">${icon("clock")}${item.transit} в пути</span>`);
    }
    if (item.mention) {
      flags.push(`<span class="kq-at-flag" aria-label="Вас упомянули">@</span>`);
    }
    if (item.muted) {
      flags.push(`<span class="kq-st" aria-label="Уведомления не доходят">${icon("bell-off")}</span>`);
    }
    if (item.unread) {
      flags.push(`<span class="kq-unread">${item.unread}</span>`);
    }
    const reach = item.saved ? "" : `<span class="kq-reach${item.live ? " is-live" : ""}">${item.live ? "<i></i>" : ""}${esc(item.reach)}</span>`;
    return `<button type="button" class="kq-row"${current ? ' aria-current="true"' : ""}>
      ${listTile(item)}
      <span class="kq-row-main">
        <span class="kq-row-top"><span class="kq-row-name">${esc(item.name)}</span>${reach}</span>
        <span class="kq-row-bottom"><span class="kq-row-time">${esc(item.time)}</span><span class="kq-row-last">${status}${esc(item.last)}</span>${flags.length ? `<span class="kq-row-flags">${flags.join("")}</span>` : ""}</span>
      </span>
    </button>`;
  }

  function renderList() {
    const keep = pillFilter ? PILL_FILTERS[pillFilter] : () => true;
    $("#air-list").innerHTML = LIST_SECTIONS.map(([key, title]) => {
      const items = LIST.filter((item) => item.section === key && keep(item));
      if (!items.length) {
        return "";
      }
      return `<p class="kq-sec-head">${title} <b>${items.length}</b></p>${items.map((item, i) => listRow(item, key === "live" && i === 0 && !pillFilter)).join("")}`;
    }).join("");
  }

  // ---- link marks, day strips, message states -------------------------------------------------------------

  function renderCells() {
    $$("[data-cells]").forEach((strip) => {
      const ranges = strip.dataset.cells.split(",").map((pair) => pair.split("-").map(Number));
      const now = Number(strip.dataset.now);
      const cells = [];
      for (let h = 0; h < 24; h += 1) {
        const on = ranges.some(([from, to]) => h >= from && h < to);
        const classes = [on ? "on" : "", h === now ? "now" : "", h === now && on ? "live" : ""].filter(Boolean).join(" ");
        cells.push(`<i${classes ? ` class="${classes}"` : ""}></i>`);
      }
      strip.innerHTML = cells.join("");
    });
  }

  const STATES = [
    ["kq-st-wait", "clock", "В пути", "Ждёт, когда собеседник появится в сети. Пока не ушло, можно изменить или отменить."],
    ["kq-st-sending", "clock", "Отправляется", "Собеседник в сети, сообщение уходит прямо сейчас."],
    ["kq-st-done", "check", "Отправлено", "Ушло с этого устройства, ждём подтверждения."],
    ["kq-st-done", "check-double", "Доставлено", "Устройство собеседника подтвердило получение."],
    ["kq-st-fail", "alert", "Не отправлено", ""],
  ];

  function renderStates() {
    $("#statuses").innerHTML = STATES.map(([cls, name, title, text]) => `
      <div class="kq-state">
        <b><span class="kq-st ${cls}">${icon(name)}</span>${title}</b>
        ${text ? `<span>${text}</span>` : `<button type="button" class="kq-btn kq-btn-quiet kq-state-retry">Повторить</button>`}
      </div>`).join("");
  }

  function renderDemoIds() {
    $$("[data-demo-id]").forEach((el) => {
      const key = el.dataset.demoId;
      el.textContent = key === "said-key" ? groupHex(IDS.said.slice(0, 64)) : groupHex(IDS[key]);
    });
  }

  // ---- key moment 1: the tray unloads when the contact comes online -----------------------------------------

  const stageTemplates = {};

  function remember(id) {
    stageTemplates[id] = $(`#${id}`).innerHTML;
  }

  function resetStage(id, button, label) {
    $(`#${id}`).innerHTML = stageTemplates[id];
    button.textContent = label;
    button.dataset.done = "";
    renderCells();
    renderDemoIds();
  }

  async function momentTray(button) {
    if (button.dataset.done) {
      resetStage("m1", button, "Амина в сети");
      return;
    }
    button.disabled = true;
    const stage = $("#m1");
    const presence = $("[data-m1-presence]", stage);
    const log = $("[data-m1-log]", stage);
    const queue = $("[data-m1-queue]", stage);
    const count = $("[data-m1-count]", stage);
    const items = $$("[data-m1-item]", stage);

    crossFade(presence, () => {
      presence.innerHTML = '<svg class="kq-lm kq-lm-live" viewBox="0 0 30 10" aria-hidden="true"><circle cx="4" cy="5" r="3"/><path d="M7 5h16"/><circle cx="26" cy="5" r="3"/></svg><span>в сети, напрямую</span>';
    });

    const stagger = duration("stagger");
    const moves = items.map(async (item, index) => {
      await wait(index * stagger);
      const before = item.getBoundingClientRect();
      const text = $(".kq-entry-text", item).textContent;
      const time = $(".kq-entry-time", item).textContent;
      item.remove();
      const entry = document.createElement("div");
      entry.className = "kq-entry is-me";
      entry.innerHTML = `<span class="kq-entry-time">${esc(time)}</span><span class="kq-entry-text">${esc(text)}</span><span class="kq-st kq-st-sending" title="Отправляется">${icon("clock")}</span>`;
      log.appendChild(entry);
      const left = items.length - index - 1;
      count.textContent = left ? `В пути: ${left} ${plural(left, "сообщение", "сообщения", "сообщений")}` : "Всё ушло";
      const after = entry.getBoundingClientRect();
      if (isCalm()) {
        await play(entry, [{ opacity: 0 }, { opacity: 1 }], { duration: duration("layout") });
      }
      else {
        await play(entry, [
          { transform: `translateY(${before.top - after.top}px)`, opacity: 0.4 },
          { transform: "translateY(0)", opacity: 1 },
        ], { duration: duration("layout") });
      }
      const mark = $(".kq-st", entry);
      await wait(duration("state"));
      mark.className = "kq-st kq-st-done";
      mark.title = "Отправлено";
      mark.innerHTML = icon("check");
      await wait(duration("state") * 2);
      mark.title = "Доставлено";
      mark.innerHTML = icon("check-double");
    });
    await Promise.all(moves);

    const height = queue.offsetHeight;
    if (isCalm()) {
      await play(queue, [{ opacity: 1 }, { opacity: 0 }], { duration: duration("layout"), fill: "forwards" });
    }
    else {
      queue.style.overflow = "hidden";
      const { paddingTop, paddingBottom } = getComputedStyle(queue);
      await play(queue, [
        { height: `${height}px`, opacity: 1, paddingTop, paddingBottom },
        { height: "0px", opacity: 0, paddingTop: "0px", paddingBottom: "0px" },
      ], { duration: duration("layout"), fill: "forwards" });
    }
    queue.hidden = true;
    queue.getAnimations().forEach((a) => a.cancel());
    button.textContent = "Сбросить";
    button.dataset.done = "1";
    button.disabled = false;
  }

  // ---- key moment 2: the person moves into "online now" ---------------------------------------------------

  async function momentMove(button) {
    if (button.dataset.done) {
      resetStage("m2", button, "Хадиджа в сети");
      return;
    }
    button.disabled = true;
    const stage = $("#m2");
    const rows = $$("[data-m2-row]", stage);
    const first = new Map(rows.map((row) => [row, row.getBoundingClientRect()]));
    const moving = $('[data-m2-row="khadija"]', stage);
    $('[data-m2-section="online"]', stage).appendChild(moving);
    $("[data-m2-online]", stage).textContent = "3";
    $("[data-m2-offline]", stage).textContent = "1";
    const reach = $("[data-m2-reach]", moving);
    reach.className = "kq-reach is-live";
    reach.innerHTML = "<i></i>в сети";

    if (isCalm()) {
      await play(moving, [{ opacity: 0 }, { opacity: 1 }], { duration: duration("layout") });
    }
    else {
      await Promise.all(rows.map((row) => {
        const before = first.get(row);
        const after = row.getBoundingClientRect();
        const dy = before.top - after.top;
        if (!dy) {
          return Promise.resolve();
        }
        const lift = row === moving ? [{ boxShadow: token("elevation-lift") }, { boxShadow: "0 0 0 transparent" }] : [{}, {}];
        return play(row, [
          { transform: `translateY(${dy}px)`, ...lift[0] },
          { transform: "translateY(0)", ...lift[1] },
        ], { duration: duration("layout") });
      }));
    }
    button.textContent = "Сбросить";
    button.dataset.done = "1";
    button.disabled = false;
  }

  // ---- key moment 3: the request is accepted, the dashed path becomes a line --------------------------------

  async function momentAccept(button) {
    if (button.dataset.done) {
      resetStage("m3", button, "Саид принял");
      return;
    }
    button.disabled = true;
    const stage = $("#m3");
    const line = $("[data-m3-line]", stage);
    const length = typeof line.getTotalLength === "function" ? line.getTotalLength() : 252;
    line.classList.remove("is-off");
    line.classList.add("is-live");
    $("[data-m3-end]", stage).classList.add("is-live");
    $("[data-m3-initials]", stage).textContent = "СН";
    $("[data-m3-name]", stage).textContent = "Саид";
    const title = $("[data-m3-title]", stage);
    const text = $("[data-m3-text]", stage);
    crossFade(title, () => {
      title.textContent = "Контакт на связи";
    });
    crossFade(text, () => {
      text.innerHTML = "<b>Саид Нуриев</b> принял запрос. Связь напрямую, можно писать.";
    });
    if (!isCalm()) {
      line.style.strokeDasharray = `${length} ${length}`;
      await play(line, [{ strokeDashoffset: length }, { strokeDashoffset: 0 }], { duration: duration("reveal") });
      line.style.strokeDasharray = "";
    }
    button.textContent = "Сбросить";
    button.dataset.done = "1";
    button.disabled = false;
  }

  // ---- key moment 4: the dossier unfolds from the conversation header ---------------------------------------

  function toggleDossier(force) {
    const stage = $("#m4");
    const open = typeof force === "boolean" ? force : !stage.classList.contains("is-open");
    stage.classList.toggle("is-open", open);
    $('[data-act="m4"]', stage).setAttribute("aria-expanded", String(open));
  }

  // ---- buttons ------------------------------------------------------------------------------------------------

  function renderButtonMatrix() {
    const kinds = [
      ["Чернильная", "kq-btn-ink", "Отправить запрос"],
      ["Обычная", "", "Сканировать QR"],
      ["Тихая", "kq-btn-quiet", "Вставить пример"],
      ["Опасная", "kq-btn-danger", "Заблокировать"],
    ];
    const states = [
      ["Обычное", ""],
      ["Наведение", " is-hover"],
      ["Фокус", " is-focus"],
      ["Нажатие", " is-active"],
      ["Загрузка", " is-busy"],
      ["Выключена", " disabled"],
    ];
    // States run down, kinds across: four columns fit any desktop width without scrolling.
    const head = `<span></span>${kinds.map(([title]) => `<span>${title}</span>`).join("")}`;
    const body = states.map(([title, state]) => `<span>${title}</span>${kinds.map(([, cls, label]) => {
      const disabled = state === " disabled";
      return `<span><button type="button" class="kq-btn ${cls}${disabled ? "" : state}" tabindex="-1"${disabled ? " disabled" : ""}${state === " is-busy" ? ' aria-busy="true"' : ""}>${label}</button></span>`;
    }).join("")}`).join("");
    $("#button-matrix").innerHTML = head + body;
  }

  let exportAttempt = 0;

  async function runBusy(button) {
    if (button.classList.contains("is-busy")) {
      return;
    }
    const error = $("[data-busy-error]");
    error.hidden = true;
    button.classList.add("is-busy");
    button.setAttribute("aria-busy", "true");
    await wait(1400);
    button.classList.remove("is-busy");
    button.removeAttribute("aria-busy");
    exportAttempt += 1;
    if (exportAttempt % 2 === 1) {
      error.hidden = false;
      if (!isCalm()) {
        play(error, [{ opacity: 0, transform: `translateY(${token("space-4")})` }, { opacity: 1, transform: "translateY(0)" }], { duration: duration("state") });
      }
    }
    else {
      toast("Профиль экспортирован");
    }
  }

  async function runProgress(button) {
    const bar = $("[data-progress]");
    const text = $("[data-progress-text]");
    button.disabled = true;
    const from = 0.62;
    const started = performance.now();
    const total = isCalm() ? 1 : 1600;
    await new Promise((resolve) => {
      function frame(now) {
        const share = Math.min(1, from + (1 - from) * ((now - started) / total));
        bar.style.transform = `scaleX(${share.toFixed(3)})`;
        text.textContent = `${Math.round(248 * share)} из 248 КБ`;
        if (share < 1) {
          requestAnimationFrame(frame);
        }
        else {
          resolve();
        }
      }
      requestAnimationFrame(frame);
    });
    text.textContent = "получен, 248 КБ";
    button.textContent = "Начать заново";
    button.disabled = false;
    button.dataset.act = "progress-reset";
  }

  function resetProgress(button) {
    $("[data-progress]").style.transform = "scaleX(0.62)";
    $("[data-progress-text]").textContent = "154 из 248 КБ";
    button.textContent = "Продолжить передачу";
    button.dataset.act = "progress";
  }

  // ---- toasts --------------------------------------------------------------------------------------------------

  function toast(text, action) {
    const box = $("#toasts");
    const el = document.createElement("div");
    el.className = "kq-toast";
    el.setAttribute("role", "status");
    el.innerHTML = `<span>${esc(text)}</span>${action ? `<button type="button">${esc(action.label)}</button>` : ""}`;
    box.appendChild(el);
    const enter = isCalm() ? [{ opacity: 0 }, { opacity: 1 }] : [{ opacity: 0, transform: `translateY(${token("space-8")})` }, { opacity: 1, transform: "translateY(0)" }];
    play(el, enter, { duration: duration("layout") });

    let timer = 0;
    const close = async () => {
      clearTimeout(timer);
      if (!el.isConnected) {
        return;
      }
      const leave = isCalm() ? [{ opacity: 1 }, { opacity: 0 }] : [{ opacity: 1, transform: "translateY(0)" }, { opacity: 0, transform: `translateY(${token("space-8")})` }];
      await play(el, leave, { duration: duration("state"), fill: "forwards" });
      el.remove();
    };
    const arm = () => {
      clearTimeout(timer);
      timer = setTimeout(close, 4000);
    };
    el.addEventListener("mouseenter", () => clearTimeout(timer));
    el.addEventListener("mouseleave", arm);
    el.addEventListener("focusin", () => clearTimeout(timer));
    el.addEventListener("focusout", arm);
    if (action) {
      $("button", el).addEventListener("click", () => {
        close();
        action.run();
      });
    }
    arm();
  }

  async function copyId() {
    try {
      await navigator.clipboard.writeText(IDS.me);
      toast("ID скопирован");
    }
    catch (err) {
      toast("Браузер не дал доступ к буферу обмена. Выделите ID и скопируйте вручную.");
    }
  }

  // ---- bottom sheet with a drag to close --------------------------------------------------------------------

  function setSheet(open) {
    const phone = $("#phone");
    const sheet = $("[data-sheet]");
    phone.classList.toggle("is-sheet", open);
    sheet.setAttribute("aria-hidden", String(!open));
    sheet.style.transform = "";
    $$("button", sheet).forEach((button) => {
      button.tabIndex = open ? 0 : -1;
    });
    if (open) {
      $("button", sheet).focus({ preventScroll: true });
    }
    else {
      $('[data-act="sheet"]').focus({ preventScroll: true });
    }
  }

  function bindSheetDrag() {
    const sheet = $("[data-sheet]");
    let startY = 0;
    let lastY = 0;
    let lastTime = 0;
    let speed = 0;
    let dragging = false;

    sheet.addEventListener("pointerdown", (event) => {
      if (event.target.closest("button")) {
        return;
      }
      dragging = true;
      startY = event.clientY;
      lastY = event.clientY;
      lastTime = event.timeStamp;
      speed = 0;
      sheet.setPointerCapture(event.pointerId);
      sheet.classList.add("is-dragging");
    });
    sheet.addEventListener("pointermove", (event) => {
      if (!dragging) {
        return;
      }
      const dy = Math.max(0, event.clientY - startY);
      const dt = Math.max(1, event.timeStamp - lastTime);
      speed = (event.clientY - lastY) / dt;
      lastY = event.clientY;
      lastTime = event.timeStamp;
      sheet.style.transform = `translateY(${dy}px)`;
    });
    const end = (event) => {
      if (!dragging) {
        return;
      }
      dragging = false;
      sheet.classList.remove("is-dragging");
      const dy = Math.max(0, event.clientY - startY);
      if (dy > sheet.offsetHeight * 0.3 || speed > 0.6) {
        setSheet(false);
      }
      else {
        sheet.style.transform = "";
      }
    };
    sheet.addEventListener("pointerup", end);
    sheet.addEventListener("pointercancel", end);
  }

  // ---- PIN -------------------------------------------------------------------------------------------------

  function bindPin() {
    const boxes = $$("[data-pin] input");
    const note = $("[data-pin-note]");
    const report = () => {
      const filled = boxes.every((box) => box.value);
      note.textContent = filled ? "Все четыре цифры набраны: приложение проверит PIN сразу, без кнопки." : "Цифра переходит в следующую клетку сама, стирание возвращает назад.";
    };
    boxes.forEach((box, index) => {
      box.addEventListener("input", () => {
        const digits = box.value.replace(/\D/g, "");
        if (digits.length > 1) {
          digits.split("").slice(0, boxes.length - index).forEach((digit, offset) => {
            boxes[index + offset].value = digit;
          });
          boxes[Math.min(boxes.length - 1, index + digits.length - 1)].focus();
        }
        else {
          box.value = digits;
          if (digits && boxes[index + 1]) {
            boxes[index + 1].focus();
          }
        }
        report();
      });
      box.addEventListener("keydown", (event) => {
        if (event.key === "Backspace" && !box.value && boxes[index - 1]) {
          boxes[index - 1].focus();
          boxes[index - 1].value = "";
          event.preventDefault();
          report();
        }
      });
    });
  }

  // ---- navigation highlight -----------------------------------------------------------------------------------

  function bindNav() {
    const links = new Map($$(".sg-nav a").map((a) => [a.getAttribute("href").slice(1), a]));
    if (!("IntersectionObserver" in window)) {
      return;
    }
    const observer = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          links.forEach((a, id) => a.setAttribute("aria-current", String(id === entry.target.id)));
        }
      });
    }, { rootMargin: "-30% 0px -60% 0px" });
    $$(".sg-sec").forEach((section) => observer.observe(section));
  }

  // ---- actions --------------------------------------------------------------------------------------------------

  const ACTIONS = {
    durations: () => runDurations(),
    m1: (button) => momentTray(button),
    m2: (button) => momentMove(button),
    m3: (button) => momentAccept(button),
    m4: () => toggleDossier(),
    "id-good": () => setIdValue(`tox:${IDS.said.toLowerCase()}`),
    "id-bad": () => setIdValue(groupHex(corrupt(IDS.said))),
    "id-own": () => setIdValue(IDS.me),
    "id-clear": () => setIdValue(""),
    "cmd-id": () => {
      setIdValue($("#cmd").value);
      $("#tox-id").scrollIntoView({ block: "center", behavior: isCalm() ? "auto" : "smooth" });
    },
    busy: (button) => runBusy(button),
    progress: (button) => runProgress(button),
    "progress-reset": (button) => resetProgress(button),
    "toast-copy": () => copyId(),
    "toast-decline": () => toast("Запрос отклонён", { label: "Отменить", run: () => toast("Запрос снова ждёт ответа") }),
    dialog: () => {
      const dialog = $("#dialog");
      if (typeof dialog.showModal === "function") {
        dialog.showModal();
      }
    },
    sheet: () => setSheet(true),
    "sheet-close": () => setSheet(false),
  };

  function setIdValue(value) {
    $("#tox-id").value = value;
    renderIdField(true);
  }

  document.addEventListener("click", (event) => {
    const target = event.target.closest("[data-act]");
    if (!target) {
      return;
    }
    const action = ACTIONS[target.dataset.act];
    if (action) {
      action(target);
    }
  });

  document.addEventListener("click", (event) => {
    const pill = event.target.closest("[data-pill]");
    if (!pill) {
      return;
    }
    const name = pill.dataset.pill;
    pillFilter = pillFilter === name ? "" : name;
    $$("[data-pill]").forEach((p) => p.setAttribute("aria-pressed", String(p.dataset.pill === pillFilter)));
    renderList();
  });

  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
      if ($("#phone").classList.contains("is-sheet")) {
        setSheet(false);
      }
      if ($("#m4").classList.contains("is-open")) {
        toggleDossier(false);
        $('[data-act="m4"]').focus({ preventScroll: true });
      }
      if (document.activeElement === $("#cmd")) {
        $("#cmd").value = "";
        renderCommand();
      }
      return;
    }
    const typing = event.target.closest("input, textarea, [contenteditable]");
    if (event.key === "/" && !typing && !event.metaKey && !event.ctrlKey && !event.altKey) {
      event.preventDefault();
      $("#cmd").focus();
    }
  });

  $("#dialog").addEventListener("close", () => {
    const blocked = $("#dialog").returnValue === "block";
    $("[data-dialog-result]").textContent = blocked ? "Зайд заблокирован. Снять блокировку можно в его досье." : "Ничего не изменилось.";
  });

  $("#tox-id").addEventListener("input", () => renderIdField(true));
  $("#cmd").addEventListener("input", renderCommand);
  $("#compose").addEventListener("input", growCompose);
  $("#compose").addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      $("[data-send]").click();
    }
  });
  $("[data-send]").addEventListener("click", () => {
    const field = $("#compose");
    if (!field.value.trim()) {
      return;
    }
    field.value = "";
    growCompose();
    field.focus();
  });

  // ---- start ----------------------------------------------------------------------------------------------------

  renderSwatches();
  renderPairs();
  renderType();
  renderGrid();
  renderMotion();
  renderButtonMatrix();
  renderList();
  renderCells();
  renderStates();
  renderDemoIds();
  renderIdField(false);
  growCompose();
  bindSheetDrag();
  bindPin();
  bindNav();
  ["m1", "m2", "m3"].forEach(remember);
  syncSegment("theme", readChoice("kq-system-theme", "system"));
  syncSegment("motion", readChoice("kq-system-motion", "system"));
  renderSwatchValues();
})();
