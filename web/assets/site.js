/* khandaq.org: the few things the pages do with a script. Every page also works without it.

   - Theme: the choice in the footer (as the system, light, dark), kept in localStorage under the
     key the bootstrap in <head> reads before the first paint.
   - Download: the visitor's own platform goes first in the list and is named on the main button.
   - The bar under the thumb on a phone steps aside while the download list is on screen.
   - The live screen in the first band: the delivery moment, played once when it comes into view.
   - The ruler under the example ID fills once.
   - Recent releases on the home page and the whole changelog page, drawn from /changelog.json.

   Only transform and opacity move, and under prefers-reduced-motion nothing moves at all: the
   stylesheet then shows the final state of the live screen by itself. */
(function () {
  'use strict';

  var root = document.documentElement;
  var lang = root.lang === 'ru' ? 'ru' : 'en';
  var calm = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);

  function each(list, fn) {
    for (var i = 0; i < list.length; i++) {
      fn(list[i], i);
    }
  }

  function wait(ms) {
    return new Promise(function (resolve) {
      window.setTimeout(resolve, ms);
    });
  }

  function token(name) {
    return window.getComputedStyle(root).getPropertyValue(name).trim();
  }

  /* A motion token in milliseconds, so the script keeps the same timing as the stylesheet. */
  function duration(name, fallback) {
    var value = token(name);
    var number = parseFloat(value);
    if (isNaN(number)) {
      return fallback;
    }
    return /ms$/.test(value) ? number : number * 1000;
  }

  function make(tag, className, text) {
    var node = document.createElement(tag);
    if (className) {
      node.className = className;
    }
    if (text !== undefined && text !== null) {
      node.textContent = text;
    }
    return node;
  }

  /* ---- theme ------------------------------------------------------------------------------------ */

  function initTheme() {
    var control = document.querySelector('[data-theme-control]');
    if (!control) {
      return;
    }
    var stored = null;
    try {
      stored = window.localStorage.getItem('kq-theme');
    } catch (e) {
      stored = null;
    }
    var current = stored === 'light' || stored === 'dark' ? stored : 'system';
    each(control.querySelectorAll('input[type="radio"]'), function (input) {
      input.checked = input.value === current;
      input.addEventListener('change', function () {
        if (!input.checked) {
          return;
        }
        if (input.value === 'system') {
          root.removeAttribute('data-theme');
        } else {
          root.setAttribute('data-theme', input.value);
        }
        try {
          if (input.value === 'system') {
            window.localStorage.removeItem('kq-theme');
          } else {
            window.localStorage.setItem('kq-theme', input.value);
          }
        } catch (e) {
          /* Storage is off (a private window): the choice holds until the page is left. */
        }
      });
    });
    control.hidden = false;
  }

  /* ---- download: the visitor's platform first ----------------------------------------------------- */

  function detectPlatform() {
    var nav = window.navigator;
    var agent = nav.userAgent || '';
    var hint = (nav.userAgentData && nav.userAgentData.platform) || nav.platform || '';
    if (/CrOS|Chrome OS|Chromium OS/i.test(agent + ' ' + hint)) {
      return null;
    }
    if (/Android/i.test(agent) || /Android/i.test(hint)) {
      return 'android';
    }
    /* iPadOS presents itself as a Mac; a Mac has no touch screen. */
    if (/iPhone|iPad|iPod/.test(agent) || /^iP/.test(hint) || (/Mac/.test(hint) && nav.maxTouchPoints > 1)) {
      return 'ios';
    }
    if (/Win/i.test(hint) || /Windows/.test(agent)) {
      return 'windows';
    }
    if (/Mac/i.test(hint) || /Macintosh/.test(agent)) {
      return 'macos';
    }
    if (/Linux/i.test(hint) || /Linux|X11/.test(agent)) {
      return 'linux';
    }
    return null;
  }

  function initDownloads() {
    var list = document.querySelector('[data-dl-list]');
    var platform = list ? detectPlatform() : null;
    var row = platform ? list.querySelector('[data-platform="' + platform + '"]') : null;
    if (!row) {
      return null;
    }
    list.insertBefore(row, list.firstElementChild);
    row.classList.add('is-yours');
    var label = row.querySelector('.dl-yours');
    if (label) {
      label.hidden = false;
    }
    var primary = row.querySelector('[data-primary]');
    return {
      label: row.getAttribute('data-cta'),
      href: row.getAttribute('data-cta-href') || (primary && primary.href) || '#download',
      note: row.getAttribute('data-note')
    };
  }

  function initActions(choice) {
    var bar = document.querySelector('[data-thumb-bar]');
    if (choice) {
      each([document.querySelector('[data-hero-cta]'), bar && bar.querySelector('a')], function (link) {
        if (link && choice.label) {
          link.textContent = choice.label;
          link.href = choice.href;
        }
      });
      var facts = document.querySelector('[data-hero-facts]');
      if (facts && choice.note) {
        facts.textContent = choice.note;
      }
    }
    var download = document.getElementById('download');
    if (!bar || !download || !('IntersectionObserver' in window)) {
      return;
    }
    /* One download button on screen at a time: the bar steps aside while the list is in view. */
    var observer = new IntersectionObserver(function (entries) {
      var inView = entries[entries.length - 1].isIntersecting;
      bar.classList.toggle('is-tucked', inView);
      bar.inert = inView;
    });
    observer.observe(download);
  }

  /* ---- the live screen: the moment of delivery ---------------------------------------------------- */

  /* Pages at least 2100 px wide are drawn with CSS zoom; a transform is in unzoomed pixels. */
  function zoomOf(node) {
    var width = node.getBoundingClientRect().width;
    return node.offsetWidth ? width / node.offsetWidth : 1;
  }

  /* Each element of the "before" layer of a swap travels to where its twin sits in the "after"
     layer. Nothing is laid out again, so nothing on the page shifts. */
  function travel(swap, options) {
    var before = swap && swap.querySelector(':scope > .is-before');
    var after = swap && swap.querySelector(':scope > .is-after');
    if (!before || !after || !before.getClientRects().length) {
      return Promise.resolve();
    }
    var zoom = zoomOf(swap) || 1;
    var ease = token('--kq-ease-out') || 'ease-out';
    var moves = [];
    each(before.querySelectorAll('[data-k]'), function (node) {
      var key = node.getAttribute('data-k');
      var twin = after.querySelector('[data-k="' + key + '"]');
      if (!twin) {
        return;
      }
      var from = node.getBoundingClientRect();
      var to = twin.getBoundingClientRect();
      var dx = (to.left - from.left) / zoom;
      var dy = (to.top - from.top) / zoom;
      if (Math.abs(dx) < 0.5 && Math.abs(dy) < 0.5) {
        return;
      }
      var lifted = options.lift === key;
      if (lifted) {
        node.classList.add('is-moving');
      }
      var motion = node.animate(
        [{ transform: 'translate(0, 0)' }, { transform: 'translate(' + dx + 'px, ' + dy + 'px)' }],
        { duration: options.duration, easing: ease, fill: 'forwards' }
      );
      moves.push(motion.finished.then(function () {
        if (lifted) {
          node.classList.remove('is-moving');
        }
      }));
    });
    return Promise.all(moves);
  }

  function setMark(mark, state) {
    mark.classList.remove('is-wait', 'is-sending', 'is-sent', 'is-done');
    mark.classList.add(state);
  }

  function play(air) {
    var reveal = duration('--kq-dur-reveal', 440);
    var state = duration('--kq-dur-state', 200);
    var stagger = duration('--kq-dur-stagger', 90);
    var list = air.querySelector('.air-list [data-swap="reached"]');
    var log = air.querySelector('[data-swap="delivered"]');
    var queue = log && log.querySelector('.is-before .kq-queue');
    var marks = queue ? queue.querySelectorAll('.kq-st') : [];

    /* A moment on the waiting state first: Amina is offline, two messages wait in the tray. */
    return wait(reveal * 2.5)
      .then(function () {
        /* She comes online: her row moves up into Online now. */
        return travel(list, { duration: reveal, lift: 'amina' });
      })
      .then(function () {
        air.classList.add('is-reached');
        return wait(reveal + state);
      })
      .then(function () {
        /* The tray unloads one message at a time: sending, sent, delivered. */
        var steps = [];
        each(marks, function (mark, i) {
          steps.push(wait(i * stagger * 4)
            .then(function () {
              setMark(mark, 'is-sending');
              return wait(stagger * 3);
            })
            .then(function () {
              setMark(mark, 'is-sent');
              return wait(stagger * 4);
            })
            .then(function () {
              setMark(mark, 'is-done');
            }));
        });
        return Promise.all(steps);
      })
      .then(function () {
        return wait(state);
      })
      .then(function () {
        if (queue) {
          queue.classList.add('is-leaving');
        }
        return travel(log, { duration: reveal });
      })
      .then(function () {
        air.classList.add('is-delivered');
      });
  }

  function whenShown(fn) {
    if (document.visibilityState !== 'hidden') {
      fn();
      return;
    }
    document.addEventListener('visibilitychange', function onShow() {
      if (document.visibilityState !== 'hidden') {
        document.removeEventListener('visibilitychange', onShow);
        fn();
      }
    });
  }

  function initAir() {
    var air = document.querySelector('[data-air]');
    if (!air || calm || !('IntersectionObserver' in window) || !air.animate) {
      return;
    }
    /* It plays when the transcript, tray included, is in plain view: on a phone that is usually a
       short scroll down, and the bar under the thumb does not count as view. */
    var screen = air.querySelector('.air-chat-body') || air;
    var bar = document.querySelector('[data-thumb-bar]');
    var covered = bar && window.getComputedStyle(bar).display !== 'none' ? bar.offsetHeight : 0;
    var played = false;
    var observer = new IntersectionObserver(function (entries) {
      each(entries, function (entry) {
        var seen = entry.intersectionRatio >= 0.9 || entry.intersectionRect.height >= window.innerHeight * 0.6;
        if (!played && entry.isIntersecting && seen) {
          played = true;
          observer.disconnect();
          whenShown(function () {
            play(air);
          });
        }
      });
    }, { threshold: [0, 0.25, 0.5, 0.75, 0.9, 1], rootMargin: '0px 0px -' + covered + 'px 0px' });
    observer.observe(screen);
  }

  /* ---- the ruler under the example ID ------------------------------------------------------------- */

  function initRuler() {
    var ruler = document.querySelector('[data-ruler]');
    if (!ruler || calm || !('IntersectionObserver' in window)) {
      return;
    }
    /* Already on screen when the page opens: leave it drawn rather than redraw it under the eye. */
    if (ruler.getBoundingClientRect().top < window.innerHeight) {
      return;
    }
    ruler.classList.add('is-waiting');
    var observer = new IntersectionObserver(function (entries) {
      if (entries[entries.length - 1].isIntersecting) {
        observer.disconnect();
        window.requestAnimationFrame(function () {
          ruler.classList.add('is-filling');
          ruler.classList.remove('is-waiting');
        });
      }
    }, { threshold: 1 });
    observer.observe(ruler);
  }

  /* ---- the changelog ------------------------------------------------------------------------------ */

  var LEVELS = ['critical', 'important', 'medium', 'low'];

  var TEXT = {
    en: {
      page: '/changelog',
      months: ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September',
        'October', 'November', 'December'],
      level: { critical: 'Critical', important: 'Important', medium: 'Medium', low: 'Low' },
      filter: { all: 'All', critical: 'Critical', important: 'Important', medium: 'Medium', low: 'Low' },
      filterGroup: 'Show changes by level',
      platform: {
        android: 'Android', ios: 'iOS', desktop: 'Desktop', windows: 'Windows', macos: 'macOS',
        linux: 'Linux', web: 'Website', network: 'Network', infra: 'Infrastructure'
      },
      count: function (total, parts) {
        return total + (total === 1 ? ' change' : ' changes') + (parts.length ? ': ' + parts.join(', ') : '');
      },
      part: function (n, level) {
        return n + ' ' + level;
      },
      partLevel: { critical: 'critical', important: 'important', medium: 'medium', low: 'low' },
      commits: 'Commits',
      nothing: 'No release has changes at this level.',
      shown: function (n) {
        return n === 1 ? 'Showing 1 release.' : 'Showing ' + n + ' releases.';
      },
      failed: 'The changelog could not be loaded right now.',
      fallback: 'Releases on GitHub',
      generated: function (date) {
        return 'Drawn from the repository on ' + date + '.';
      }
    },
    ru: {
      page: '/ru/changelog.html',
      months: ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября',
        'октября', 'ноября', 'декабря'],
      level: { critical: 'Критичное', important: 'Важное', medium: 'Среднее', low: 'Мелкое' },
      filter: { all: 'Все', critical: 'Критичные', important: 'Важные', medium: 'Средние', low: 'Мелкие' },
      filterGroup: 'Показать изменения по уровню',
      platform: {
        android: 'Android', ios: 'iOS', desktop: 'Компьютер', windows: 'Windows', macos: 'macOS',
        linux: 'Linux', web: 'Сайт', network: 'Сеть', infra: 'Инфраструктура'
      },
      count: function (total, parts) {
        return 'Изменений: ' + total + (parts.length ? ' (' + parts.join(', ') + ')' : '');
      },
      part: function (n, level) {
        return level + ': ' + n;
      },
      partLevel: { critical: 'критичных', important: 'важных', medium: 'средних', low: 'мелких' },
      commits: 'Коммиты',
      nothing: 'Ни в одном выпуске нет изменений такого уровня.',
      shown: function (n) {
        return 'Выпусков показано: ' + n + '.';
      },
      failed: 'Журнал изменений сейчас не загрузился.',
      fallback: 'Выпуски на GitHub',
      generated: function (date) {
        return 'Собрано из репозитория ' + date + '.';
      }
    }
  };

  var text = TEXT[lang];

  /* The release notes are written elsewhere and can carry typography this site does not use:
     dashes, the one-character ellipsis, arrows, the middle dot. They are rewritten in plain
     characters on the way to the page; the source text is not changed. */
  var ARROW = '[\\u2190-\\u21ff\\u27f0-\\u27ff\\u2900-\\u297f\\u2794-\\u27bf\\u2b00-\\u2b4f]';

  function plain(value) {
    return String(value || '')
      .replace(/(\d)\s*[\u2012\u2013]\s*(\d)/g, '$1-$2')
      .replace(/[\u2012-\u2015]/g, '-')
      .replace(/\u2026/g, '...')
      .replace(/\s*\u2194\s*/g, '-')
      .replace(new RegExp('\\s*' + ARROW + '\\s*(?=\\d)', 'g'), ' to ')
      .replace(new RegExp('\\s*' + ARROW + '\\s*', 'g'), ' > ')
      .replace(/\s*[\u00b7\u2022\u2219\u22c5\u30fb]\s*/g, ', ')
      .replace(/\u00d7/g, 'x')
      .replace(/[\u2500-\u25ff\u2610-\u2612\u2705\u2713-\u2718\u274c\u274e]/g, '');
  }

  function formatDate(iso) {
    var parts = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso || '');
    if (!parts) {
      return iso || '';
    }
    return parseInt(parts[3], 10) + '\u00a0' + text.months[parseInt(parts[2], 10) - 1] + '\u00a0' + parts[1];
  }

  function english(node) {
    if (lang !== 'en') {
      node.lang = 'en';
    }
    return node;
  }

  function byLevel(a, b) {
    return LEVELS.indexOf(a.severity) - LEVELS.indexOf(b.severity);
  }

  function countLine(changes) {
    var tally = {};
    each(changes, function (change) {
      tally[change.severity] = (tally[change.severity] || 0) + 1;
    });
    var parts = [];
    each(LEVELS, function (level) {
      if (tally[level]) {
        parts.push(text.part(tally[level], text.partLevel[level]));
      }
    });
    return text.count(changes.length, parts);
  }

  function platformsOf(changes) {
    var seen = [];
    each(changes, function (change) {
      each(change.platforms || [], function (name) {
        var label = text.platform[name] || name;
        if (seen.indexOf(label) < 0) {
          seen.push(label);
        }
      });
    });
    return seen;
  }

  function loadChangelog() {
    return fetch('/changelog.json', { cache: 'no-cache' }).then(function (response) {
      if (!response.ok) {
        throw new Error('HTTP ' + response.status);
      }
      return response.json();
    });
  }

  function renderEntry(change) {
    var item = make('li', 'cl-entry');
    var meta = make('p', 'cl-entry-meta');
    meta.appendChild(make('b', null, text.level[change.severity] || change.severity));
    var platforms = platformsOf([change]);
    if (platforms.length) {
      meta.appendChild(document.createTextNode(', ' + platforms.join(', ')));
    }
    item.appendChild(meta);
    item.appendChild(english(make('h3', 'cl-entry-title', plain(change.title))));
    if (change.description) {
      item.appendChild(english(make('p', 'cl-entry-text', plain(change.description))));
    }
    return item;
  }

  function renderRelease(release, filter) {
    var changes = (release.changes || []).slice().sort(byLevel);
    var shown = changes.filter(function (change) {
      return filter === 'all' || change.severity === filter;
    });
    if (filter !== 'all' && !shown.length) {
      return null;
    }
    var article = make('article', 'cl-release');
    var when = make('div', 'cl-when');
    var time = make('time', null, formatDate(release.date));
    time.dateTime = release.date || '';
    if (release.version) {
      article.id = 'v' + release.version;
      when.appendChild(make('h2', null, 'v' + release.version));
      when.appendChild(time);
    } else {
      var heading = make('h2');
      heading.appendChild(time);
      when.appendChild(heading);
    }
    article.appendChild(when);

    var body = make('div', 'cl-body');
    if (release.title) {
      body.appendChild(english(make('p', 'cl-title', plain(release.title))));
    }
    if (release.summary) {
      body.appendChild(english(make('p', null, plain(release.summary))));
    }
    if (changes.length) {
      body.appendChild(make('p', 'cl-count', countLine(changes)));
    }
    var list = make('ol', 'cl-list');
    each(shown, function (change) {
      list.appendChild(renderEntry(change));
    });
    if (list.children.length) {
      body.appendChild(list);
    }
    var commits = (release.commits || []).filter(function (commit) {
      return /^https:\/\/github\.com\//.test(commit.url || '') && commit.hash;
    });
    if (commits.length) {
      var line = make('p', 'cl-commits');
      line.appendChild(make('span', null, text.commits));
      each(commits, function (commit) {
        var link = make('a', null, commit.hash);
        link.href = commit.url;
        line.appendChild(link);
      });
      body.appendChild(line);
    }
    article.appendChild(body);
    return article;
  }

  function initChangelog() {
    var target = document.querySelector('[data-changelog]');
    if (!target) {
      return;
    }
    var filters = document.querySelector('[data-cl-filters]');
    var status = document.querySelector('[data-cl-status]');
    var data = null;
    var filter = 'all';

    function draw() {
      var fragment = document.createDocumentFragment();
      var count = 0;
      each(data.releases || [], function (release) {
        var node = renderRelease(release, filter);
        if (node) {
          fragment.appendChild(node);
          count += 1;
        }
      });
      each(data.days || [], function (day) {
        var node = renderRelease(day, filter);
        if (node) {
          fragment.appendChild(node);
          count += 1;
        }
      });
      if (!count) {
        fragment.appendChild(make('p', 'cl-status', text.nothing));
      }
      if (data.generated_at) {
        fragment.appendChild(make('p', 'cl-status', text.generated(formatDate(data.generated_at))));
      }
      target.textContent = '';
      target.appendChild(fragment);
      return count;
    }

    function drawFilters() {
      if (!filters) {
        return;
      }
      filters.setAttribute('role', 'group');
      filters.setAttribute('aria-label', text.filterGroup);
      var known = (data.severities || []).map(function (level) {
        return level.id;
      });
      each(['all'].concat(LEVELS), function (level) {
        if (level !== 'all' && known.length && known.indexOf(level) < 0) {
          return;
        }
        var button = make('button', 'cl-filter', text.filter[level]);
        button.type = 'button';
        button.setAttribute('aria-pressed', level === filter ? 'true' : 'false');
        button.addEventListener('click', function () {
          filter = level;
          each(filters.querySelectorAll('button'), function (other) {
            other.setAttribute('aria-pressed', other === button ? 'true' : 'false');
          });
          var count = draw();
          if (status) {
            status.textContent = text.shown(count);
          }
        });
        filters.appendChild(button);
      });
    }

    loadChangelog()
      .then(function (json) {
        data = json;
        drawFilters();
        draw();
        var id = decodeURIComponent((window.location.hash || '').slice(1));
        var anchor = id ? document.getElementById(id) : null;
        if (anchor) {
          anchor.scrollIntoView();
        }
      })
      .catch(function () {
        var message = make('p', 'cl-status', text.failed + ' ');
        var link = make('a', null, text.fallback);
        link.href = 'https://github.com/ummalife/khandaq/releases';
        message.appendChild(link);
        target.textContent = '';
        target.appendChild(message);
      });
  }

  /* The three newest releases on the home page, fetched only when that band comes near. */
  function initRecent() {
    var list = document.querySelector('[data-recent]');
    if (!list || !('IntersectionObserver' in window) || !window.fetch) {
      return;
    }
    var observer = new IntersectionObserver(function (entries) {
      if (!entries[entries.length - 1].isIntersecting) {
        return;
      }
      observer.disconnect();
      loadChangelog().then(function (data) {
        each((data.releases || []).slice(0, 3), function (release) {
          var item = make('li', 'recent-item');
          var time = make('time', null, formatDate(release.date));
          time.dateTime = release.date || '';
          var link = english(make('a', 'recent-title', plain(release.title)));
          link.href = text.page + '#v' + release.version;
          var meta = ['v' + release.version].concat(platformsOf(release.changes || []));
          item.appendChild(time);
          item.appendChild(link);
          item.appendChild(make('p', 'meta', meta.join(', ')));
          list.appendChild(item);
        });
        if (list.children.length) {
          list.hidden = false;
        }
      }).catch(function () {
        /* The link to the changelog below the list still works. */
      });
    }, { rootMargin: '600px 0px' });
    observer.observe(list.parentNode);
  }

  initTheme();
  initActions(initDownloads());
  initAir();
  initRuler();
  initRecent();
  initChangelog();
})();
